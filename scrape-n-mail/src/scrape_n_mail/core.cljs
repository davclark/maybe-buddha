(ns scrape-n-mail.core
    (:require [rum.core :as rum]
              [cljs-http.client :as http]
              [cljs.core.async :refer [<!]])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    )

(enable-console-print!)

(println "This text is printed from src/scrape-n-mail/core.cljs. Edit it and see reloading in action.")

;; define your app data so that it doesn't get over-written on reload

(defonce app-data (atom {:signed-in? false
                         :sheet-data nil}))

(defn get-google-token []
  (.. js/gapi.auth2 getAuthInstance -currentUser get getAuthResponse -id_token))

(defn init-client [gapi_config]
  (-> (.init js/gapi.client gapi_config)
      (.then (let [is-signed-in (.. js/gapi.auth2 getAuthInstance -isSignedIn)]
               ; Set up a listener per Google's tutorial
               (.listen is-signed-in #(swap! app-data assoc :signed-in? %))
               ; Also set the initial value
               (->> (.get is-signed-in)
                    (swap! app-data assoc :signed-in?) )))))

(go (let [keys-resp (<! (http/get "keys.json"))]
      (if (:success keys-resp)
        (let [config-keys 
              (->
                {:discoveryDocs ["https://sheets.googleapis.com/$discovery/rest?version=v4"]
                 :scope "https://www.googleapis.com/auth/spreadsheets.readonly"}
                (merge (:body keys-resp))
                clj->js )]
          (.load js/gapi "client:auth2" #(init-client config-keys)) )
        (println keys-resp) )))

(rum/defc hello-world < rum/reactive []
  [:div
    [:h2 "Wellness Scraper"]
    [:p "First, we will authenticate you to Google, then get some data from the Wellenss spreadsheet"]
    (if-not (:signed-in? (rum/react app-data))
      [:button {:id "authorize-button" :on-click #(.. js/gapi.auth2 getAuthInstance signIn)} "Authorize"]
      [:button {:id "sign-out-button" :on-click #(.. js/gapi.auth2 getAuthInstance signOut)} "Sign Out"]
      )
    [:pre {:id "content"}]
    ; [:h1 (:text @app-state)]
    ; [:h3 "Edit this and watch it change!"]
  ])

(rum/mount (hello-world)
           (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)

; (js/alert "Am I connected to Figwheel?")
