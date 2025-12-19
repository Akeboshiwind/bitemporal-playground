(ns app.ably
  (:require ["ably" :as Ably]))

;; >> Client ID (unique per tab/session)

(def client-id (str (random-uuid)))

;; >> Ably Client Setup

(def state (atom {:ably nil :connection-status "initialized"}))

(defn ably-client [] (:ably @state))
(defn connection-status [] (:connection-status @state))
(defn connected? [] (= "connected" (:connection-status @state)))

(def Realtime (.-Realtime (.-default Ably)))

(defn init! [api-key]
  (let [client (new Realtime #js {:key api-key
                                  :clientId client-id})]
    (.on (.-connection client)
         (fn [state-change]
           (let [status (.-current state-change)]
             (swap! state assoc :connection-status status))))
    (swap! state assoc :ably client)
    client))

;; >> Channels

(defn channel [name]
  (.get (.-channels (ably-client)) name))

;; >> Presence Helpers

(defn enter-presence!
  ([channel-name] (enter-presence! channel-name nil))
  ([channel-name data]
   (-> (.enter (.-presence (channel channel-name)) data)
       (.catch #(println "Presence enter failed:" %)))))

(defn update-presence! [channel-name data]
  (-> (.update (.-presence (channel channel-name)) data)
      (.catch #(println "Presence update failed:" %))))

(defn on-presence-change! [channel-name callback]
  (let [presence (.-presence (channel channel-name))]
    (.subscribe presence (fn [_] (callback)))
    (.once (.-connection (ably-client)) "connected" callback)))

(defn get-presence-members [channel-name callback]
  (-> (.get (.-presence (channel channel-name)))
      (.then (fn [members] (callback (vec members))))
      (.catch #(println "Get presence failed:" %))))

;; >> Pub/Sub Helpers

(defn publish! [channel-name event data]
  (-> (.publish (channel channel-name) event data)
      (.catch #(println "Publish failed:" %))))

(defn subscribe! [channel-name callback]
  (.subscribe (channel channel-name) callback))
