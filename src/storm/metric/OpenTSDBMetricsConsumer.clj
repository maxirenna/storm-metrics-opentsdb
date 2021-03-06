(ns storm.metric.OpenTSDBMetricsConsumer
  (:import (java.net Socket)
           (java.io DataOutputStream
                    OutputStreamWriter)
           (java.nio.charset Charset)
           (java.util Map)
           (backtype.storm.task TopologyContext IErrorReporter)
           (backtype.storm Config)
           (backtype.storm.metric.api IMetricsConsumer$TaskInfo))
  (:use [backtype.storm log])
  (:gen-class :name storm.metric.OpenTSDBMetricsConsumer
              :implements [backtype.storm.metric.api.IMetricsConsumer]
              :methods [^:static [makeConfig [String Integer String] java.util.Map]]))

(def tsd-host-key "metrics.opentsdb.tsd_host")
(def tsd-port-key "metrics.opentsdb.tsd_port")
(def tsd-prefix-key "metrics.opentsdb.tsd_prefix")

;; Sockets and Streams that are used to write data to openTSDB.
;; connect to your localhost and use netcat to debug the output.
(def socket (ref nil))
(def stream (ref nil))
(def writer (ref nil))

(def metric-id-header (ref nil))

(defn- connect [^String tsd-host
               ^Integer tsd-port]
  (dosync
    (ref-set socket (Socket. tsd-host tsd-port))
    (ref-set stream (DataOutputStream. (.getOutputStream @socket)))
    (ref-set writer (OutputStreamWriter. @stream (Charset/forName "UTF-8")))
    (log-message "Created socket: " (bean @socket)
    (log-message "Created stream:" (bean @stream)))))

(defn- disconnect []
  (dosync
    (.close @stream)
    (.close @socket)
    (ref-set stream nil)
    (ref-set socket nil)
    (log-message "Closed socket and stream.")))

(defn- send-data [data]
  (let [data (str "put " data "\n")]  ;"\r\n"
    ;(log-message "Sending: " data)
    (.write @writer data )
    (.flush @writer)
    ))

(defn- expand-complex-datapoint
  [dp]
  (if (or (map? (.value dp))
          (instance? java.util.AbstractMap (.value dp)))
    (vec (for [[k v] (.value dp)]
               [(str (.name dp) "/" k) v]))
    [[(.name dp) (.value dp)]]))

(defn normalize-kafka-metric-names
  " Since kafka metrics have names like following
   Partition{host=kafka-host:9092, partition=2}

   And we want to extract partition id from them, this method is used."
  [kafka-metric]
  (if-let [match (re-find #"(Partition\{host=.*,\spartition=(\d*)\}/).*" kafka-metric)]
      (str (clojure.string/replace kafka-metric #"Partition\{host=.*,\spartition=\d*\}/" "")
           " partition="
           (nth match 2))
      (if-let [match2 (re-find #"partition_(\d*)/" kafka-metric)]
         (str (clojure.string/replace kafka-metric #"partition_\d*/" "")
              " partition=" (nth match2 1))
          ;; else - return unchanged metric
          kafka-metric)))

(defn datapoint-to-metrics
  "Transforms storms datapoints to opentsdb metrics format"
  [metric-id-header
   timestamp
   tags
   datapoint]

  ; The metrics are received in data points for task.
  ; Next values are in task id:
  ; - timestamp
  ; - srcWorkerHost
  ; - srcWorkerPort
  ; - srcTaskId
  ; - srcComponentId
  ; The data point has name and value.
  ; datapoint can be either a value with own name
  ; or a map in case of multi-count metrics

  ;; TODO: should tags be with underlines or minuses?
  ;; Is there a convention?...

  ;; TODO: handle storm-kafka metrics special case
  ;; https://github.com/apache/storm/blob/b2a8a77c3b307137527c706d0cd7635a6afe25bf/external/storm-kafka/src/jvm/storm/kafka/KafkaUtils.java
  (let [metric-id (str metric-id-header "." (.name datapoint))
        obj (.value datapoint)]
    ;; todo: do case with types
    (if (number? obj)
      ;; datapoint is a Numberic value
      (str metric-id " " timestamp " " obj " " tags)
      ;; datapoint is a map of key-values
      (if (map? obj)
        (flatten (map (fn [[key val]] (str metric-id "." key " "
                                           timestamp " "
                                           val " "
                                           tags))
                      obj))
        ;; datapoint value is a collection of other datapoints? Could this happen?
        (if (coll? obj)
          (throw (Exception. (str "Failed to parse coll metric: " obj)))
          (if (instance? java.util.HashMap obj)
            ;; skip the empty metric and process only maps
            (when-not (.isEmpty obj)
              (flatten (map (fn [[key val]] (str metric-id "." key " "
                                                 timestamp " "
                                                 val " "
                                                 tags))
                            obj)))
            (throw (Exception. (str "Failed to parse metric: Not expected type: " (type obj) ": " obj )))))))))

;; it is not possible to create static fields in class in clojure
;; like in is made in storm's Config, but it works with static method.
;; http://stackoverflow.com/questions/16252783/is-it-possible-to-use-clojures-gen-class-macro-to-generate-a-class-with-static?rq=1
(defn ^Map -makeConfig
  "Construct registration argument for OpenTSDBMetricsConsumer using the predefined key names."
  [tsd_host tsd_port tsd_prefix]
  {tsd-host-key tsd_host
   tsd-port-key tsd_port
   tsd-prefix-key tsd_prefix})

(defn -prepare
  [this
   ^Map topology-config
   ^Object consumer-config         ;; aka registrationArgument
   ^TopologyContext context
   ^IErrorReporter error-reporter]
  (assert (instance? Map consumer-config))
  ;; TODO: check that registrationArgument should be a Map??? Should it be (not) a map?

  (let [cc consumer-config
        default-tsd-port (int 4242)
        default-tsd-prefix "storm.metrics."
        topology-name (get topology-config Config/TOPOLOGY_NAME)
        ;; TODO: assert the values are set
        tsd-host (get cc tsd-host-key)
        tsd-prefix (get cc tsd-prefix-key default-tsd-prefix)
        tsd-prefix (if (not= \. (last tsd-prefix))
                     (str tsd-prefix ".")
                     tsd-prefix)
        tsd-port (get cc tsd-port-key default-tsd-port)]
    (dosync (ref-set metric-id-header (str tsd-prefix       ;; the point to the end of metric-id-header will be added during conversion
                                           topology-name)))
    (connect tsd-host tsd-port)))

;; TODO: Improve -  processed metrics can be added to buffer, where they can be read from with async routines, this may improve the througput.

(defn -handleDataPoints
  [this
   ^IMetricsConsumer$TaskInfo taskinfo
   datapoints]       ; ^Collection<DataPoint>
  (let [timestamp (str (.timestamp taskinfo))
        tags (str "host=" (.srcWorkerHost taskinfo) " "
                  "port=" (.srcWorkerPort taskinfo) " "
                  "task-id=" (.srcTaskId taskinfo) " "
                  "component-id=" (.srcComponentId taskinfo))
        metrics (->> datapoints
                     (map #(datapoint-to-metrics @metric-id-header timestamp tags %))
                     (flatten )
                     (filter (complement nil?)) ;; filter out nil's
                     ;(filter (complement #(re-find #"__" %))) ;; filter out storm system metrics TODO: add this option to parameter
                     (map normalize-kafka-metric-names))]
  (doseq [m metrics] (send-data m))))

(defn -cleanup [this]
 (disconnect))
