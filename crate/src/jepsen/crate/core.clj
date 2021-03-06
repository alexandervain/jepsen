(ns jepsen.crate.core
  (:require [jepsen [core         :as jepsen]
                    [db           :as db]
                    [control      :as c :refer [|]]
                    [checker      :as checker]
                    [cli          :as cli]
                    [client       :as client]
                    [generator    :as gen]
                    [independent  :as independent]
                    [nemesis      :as nemesis]
                    [net          :as net]
                    [tests        :as tests]
                    [util         :as util :refer [meh
                                                   timeout
                                                   with-retry]]
                    [os           :as os]]
            [jepsen.os.debian     :as debian]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util  :as cu]
            [jepsen.control.net   :as cnet]
            [cheshire.core        :as json]
            [clojure.string       :as str]
            [clojure.java.jdbc    :as j]
            [clojure.java.io      :as io]
            [clojure.java.shell   :refer [sh]]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [clj-http.client          :as http]
            [knossos.op           :as op])
  (:import (java.net InetAddress)
           (io.crate.shade.org.postgresql.util PSQLException)
           (org.elasticsearch.rest RestStatus)
           (org.elasticsearch.common.unit TimeValue)
           (org.elasticsearch.common.settings
             Settings)
           (org.elasticsearch.common.transport
             InetSocketTransportAddress)
           (org.elasticsearch.client.transport
             TransportClient)
           (org.elasticsearch.transport.client
             PreBuiltTransportClient)))

(def user "crate")
(def base-dir "/opt/crate")
(def pidfile "/tmp/crate.pid")
(def stdout-logfile (str base-dir "/logs/stdout.log"))

(defn map->kw-map
  "Turns any map into a kw-keyed persistent map."
  [x]
  (condp instance? x
    java.util.Map
    (reduce (fn [m pair]
              (assoc m (keyword (key pair)) (val pair)))
            {}
            x)

    java.util.List
    (map map->kw-map x)

    true
    x))

;; ES client

(defn ^TransportClient es-connect-
  "Open an elasticsearch connection to a node."
  [node]
  (..  (PreBuiltTransportClient.
         (.. (Settings/builder)
             (put "cluster.name" "crate")
             (put "client.transport.sniff" false)
             (build))
         (make-array Class 0))
      (addTransportAddress (InetSocketTransportAddress.
                             (InetAddress/getByName (name node)) 44300))))

(defn es-connect
  "Opens an ES connection to a node, and ensures that it actually works"
  [node]
  (let [c (es-connect- node)]
    (util/with-retry [i 10]
      (-> c
          (.admin)
          (.cluster)
          (.prepareState)
          (.all)
          (.execute)
          (.actionGet))
      c
      (catch org.elasticsearch.client.transport.NoNodeAvailableException e
        (when (zero? i)
          (throw e))
        (info "Client not ready:" (type e))
        (Thread/sleep 5000)
        (retry (dec i))))))

(defn es-index!
  "Index a record"
  [^TransportClient client index type doc]
  (assert (:id doc))
  (let [res (-> client
                (.prepareIndex index type (str (:id doc)))
                (.setSource (json/generate-string doc))
                (.get))]; why not execute/actionGet?
    (when-not (= RestStatus/CREATED (.status res))
      (throw (RuntimeException. "Document not created")))
    res))

(defn es-get
  "Get a record by ID. Returns nil when record does not exist."
  [^TransportClient client index type id]
  (let [res (-> client
                (.prepareGet index type (str id))
                (.get))]
    (when (.isExists res)
      {:index   (.getIndex res)
       :type    (.getType res)
       :id      (.getId res)
       :version (.getVersion res)
       :source  (map->kw-map (.getSource res))})))

(defn es-search
  [^TransportClient client]
  (loop [results []
         scroll  (-> client
                     (.prepareSearch (into-array String []))
                     (.setScroll (TimeValue. 60000))
                     (.setSize 128)
                     (.execute)
                     (.actionGet))]
    (let [hits (.getHits (.getHits scroll))]
      (if (zero? (count hits))
        ; Done
        results

        (recur
          (->> hits
               seq
               (map (fn [hit]
                      {:id      (.id hit)
                       :version (.version hit)
                       :source  (map->kw-map (.getSource hit))}))
               (into results))
          (-> client
              (.prepareSearchScroll (.getScrollId scroll))
              (.setScroll (TimeValue. 60000))
              (.execute)
              (.actionGet)))))))

(def cratedb-spec {:dbtype "crate"
                   :dbname "test"
                   :classname "io.crate.client.jdbc.CrateDriver"
                   :user "crate"
                   :password ""
                   :port 55432})

(defn get-node-db-spec
  "Creates the db spec for the provided node"
  [node]
  (merge cratedb-spec {:host (name node)}))

(defn wait
  "Waits for crate to be healthy on the current node. Color is red,
  yellow, or green; timeout is in seconds."
  [node timeout-secs color]
  (timeout (* 1000 timeout-secs)
           (throw (RuntimeException.
                    (str "Timed out after "
                         timeout-secs
                         " s waiting for crate cluster recovery")))
           (util/with-retry []
             (-> (str "http://" (name node) ":44200/_cluster/health/?"
                      "wait_for_status=" (name color)
                      "&timeout=" timeout-secs "s")
                 (http/get {:as :json})
                 :status
                 (= 200)
                 (or (retry)))
             (catch java.io.IOException e
               (retry)))))

;; DB

(defn install-open-jdk8!
  "Installs open jdk8"
  []
  (c/su
    (debian/add-repo!
      "backports"
      "deb http://http.debian.net/debian jessie-backports main")
      (c/exec :apt-get :update)
      (c/exec :apt-get :install :-y :-t :jessie-backports "openjdk-8-jdk")
      (c/exec :update-java-alternatives :--set "java-1.8.0-openjdk-amd64")
    ))

(defn install!
  "Install crate."
  [node tarball-url]
  (c/su
    (debian/install [:apt-transport-https])
    (install-open-jdk8!)
    (cu/ensure-user! user)
    (cu/install-tarball! node tarball-url base-dir false)
    (c/exec :chown :-R (str user ":" user) base-dir))
  (info node "crate installed"))

(defn majority
  "n/2+1"
  [n]
  (-> n (/ 2) inc Math/floor long))

(defn configure!
  "Set up config files."
  [node test]
  (c/su
    (c/exec :echo
            (-> "crate.yml"
                io/resource
                slurp
                (str/replace "$NAME" (name node))
                (str/replace "$HOST" (.getHostAddress
                                       (InetAddress/getByName (name node))))
                (str/replace "$N" (str (count (:nodes test))))
                (str/replace "$MAJORITY" (str (majority (count (:nodes test)))))
                (str/replace "$UNICAST_HOSTS"
                             (clojure.string/join ", " (map (fn [node]
                                                              (str "\"" (name node) ":44300\"" ))
                                                            (:nodes test))))
                )
            :> (str base-dir "/config/crate.yml"))

      (c/exec :echo
            (-> "crate.in.sh"
                io/resource
                slurp)
            :> (str base-dir "/bin/crate.in.sh")))
  (info node "configured"))

(defn start!
  [node]
  (info node "starting crate")
  (c/su (c/exec :sysctl :-w "vm.max_map_count=262144"))
  (c/cd base-dir
        (c/sudo user
                (c/exec :mkdir :-p (str base-dir "/logs"))
                (cu/start-daemon!
                  {:logfile stdout-logfile
                   :pidfile pidfile
                   :chdir   base-dir}
                  "bin/crate")))
  (wait node 90 :green)
  (info node "crate started"))

(defn db
  [tarball-url]
  (reify db/DB
    (setup! [_ test node]
      (doto node
        (install! tarball-url)
        (configure! test)
        (start!)))

    (teardown! [_ test node]
      (cu/grepkill! "crate")
      (info node "killed")
      (c/exec :rm :-rf (c/lit (str base-dir "/logs/*")))
      (c/exec :rm :-rf (c/lit (str base-dir "/data/*"))))

    db/LogFiles
    (log-files [_ test node]
      [(str base-dir "/logs/crate.log")])))

(defmacro with-errors
  "Unified error handling: takes an operation, evaluates body in a try/catch,
  and maps common exceptions to short errors."
  [op & body]
  `(try ~@body
        (catch PSQLException e#
          (cond
            (and (= 0 (.getErrorCode e#))
                 (re-find #"blocked by: \[.+no master\];" (str e#)))
            (assoc ~op :type :fail, :error :no-master)

            (and (= 0 (.getErrorCode e#))
                 (re-find #"document with the same primary key" (str e#)))
            (assoc ~op :type :fail, :error :duplicate-key)

            (and (= 0 (.getErrorCode e#))
                 (re-find #"rejected execution" (str e#)))
            (do ; Back off a bit
                (Thread/sleep 1000)
                (assoc ~op :type :info, :error :rejected-execution))

            :else
            (throw e#)))))
