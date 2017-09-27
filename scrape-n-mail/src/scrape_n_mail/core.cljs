(ns scrape-n-mail.core
    (:require [rum.core :as rum]
              [cljs-http.client :as http]
              [clojure.string :as s]
              [cljs.core.async :refer [<!]])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    )

(enable-console-print!)

(println "This text is printed from src/scrape-n-mail/core.cljs. Edit it and see reloading in action.")

;; define your app data so that it doesn't get over-written on reload

(defonce app-data (atom {:initialized false
                         :signed-in? false
                         :sheet-data nil}))
(defonce wellbeing-sheet "1bv6vgW-HMTz0uhKDkrJoTg387b-WK6IFkFd9y6N96hA")

(defn get-wellbeing-data []
  (->
    (.get js/gapi.client.sheets.spreadsheets.values
          (clj->js {:spreadsheetId wellbeing-sheet
                    :range "Form Responses!A1:F"}) )
    ; values is the parsed version
    (.then #(swap! app-data assoc :sheet-data (.. % -result -values))) ))


; This gets registered below as the callback for when authorization state changes
; It's also called once after the gapi client is set up to initialize things if we're already logged in
(defn update-after-auth [is-signed-in]
  (swap! app-data assoc :signed-in? is-signed-in)
  (if is-signed-in
    (get-wellbeing-data) 
    (swap! app-data assoc :sheet-data nil) ))


; This gets sent as a callback into the .load call for the gapi below in the "go" block
(defn init-client [gapi_config]
  (-> (.init js/gapi.client gapi_config)
      ; I don't actually care about the response, but I need to do something with it maybe?
      (.then #(let [response %
                    is-signed-in (.. js/gapi.auth2 getAuthInstance -isSignedIn)]
               ; Set up a listener per Google's tutorial
               (.listen is-signed-in update-after-auth)
               ; Also set the initial value
               ; Unfortunrately, this often doesn't work if we were already signed in...
               ; I'm missing something
               (update-after-auth (.get is-signed-in)) 
               (swap! app-data assoc :initialized true) )
             ; This is an error reason
             #(println %)
             )))

; In retrospect, this was not really useful (these keys aren't secret).
; We're also hurting performance a little, but it's not relevant to our 
; use-case so I'm leaving it alone.
; (defonce _ ; doesn't seem to fix the reload problem with sheets not being defined.
(go (let [keys-resp (<! (http/get "keys.json"))]
      (if (:success keys-resp)
        (let [config-keys 
              (->
                {:discoveryDocs ["https://sheets.googleapis.com/$discovery/rest?version=v4"]
                 :scope "https://www.googleapis.com/auth/spreadsheets.readonly"}
                (merge (:body keys-resp))
                ; We definitely need this to undo the auto-converstion to EDN
                ; that http/get does
                clj->js )]
          (.load js/gapi "client:auth2" #(init-client config-keys)) )
        #_(println keys-resp) )))

(rum/defc hello-world < rum/reactive []
  [:div
    [:h2 "Wellness Scraper"]
    [:p "First, we will authenticate you to Google, then get some data from the Wellenss spreadsheet"]
    (if (:initialized (rum/react app-data))
      (if-not (:signed-in? @app-data)
        [:button {:id "authorize-button" :on-click #(.. js/gapi.auth2 getAuthInstance signIn)} "Authorize"]
        [:button {:id "sign-out-button" :on-click #(.. js/gapi.auth2 getAuthInstance signOut)} "Sign Out"]
        ) )
    ; Since I already react to app-data above, I don't seem to need to again...
    [:pre {:id "content"} 
     (->> (:sheet-data @app-data)
          ; For now, this is the criterion for which folks I look at
          ; It'd be nice to figure out which are hiddne to play nicer with Svani's workflow
          (filter #(s/includes? (aget % 0) "2017"))
          ; Each line is a list, this joins them in to one string
          (map #(s/join " " %))
          ; Then we join the lines
          (s/join "\n") )
          ]
  ])

(rum/mount (hello-world)
           (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)

; (js/alert "Am I connected to Figwheel?")
