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
(defonce wellbeing-sheet "1bv6vgW-HMTz0uhKDkrJoTg387b-WK6IFkFd9y6N96hA")

(defn get-wellbeing-data []
  (->
    (.get js/gapi.client.sheets.spreadsheets.values
          (clj->js {:spreadsheetId wellbeing-sheet
                     :range "Form Responses!A1:F"}) )
    (.then #(swap! app-data assoc :sheet-data (.. % -result -values))) ))


(defn update-after-auth [is-signed-in]
  (swap! app-data assoc :signed-in? is-signed-in)
  (if is-signed-in
    (get-wellbeing-data) 
    (swap! app-data assoc :sheet-data nil) ))


; This gets sent as a callback into the .load call for the gapi below in the "go" block
(defn init-client [gapi_config]
  (-> (.init js/gapi.client gapi_config)
      (.then (let [is-signed-in (.. js/gapi.auth2 getAuthInstance -isSignedIn)]
               ; Set up a listener per Google's tutorial
               (.listen is-signed-in update-after-auth)
               ; Also set the initial value
               (update-after-auth  (.get is-signed-in)) ))))

; In retrospect, this was not really useful (these keys aren't secret).
; We're also hurting performance a little, but it's not relevant to our 
; use-case so I'm leaving it alone.
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
