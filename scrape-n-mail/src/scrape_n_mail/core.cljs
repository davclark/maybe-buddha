(ns scrape-n-mail.core
    (:require [rum.core :as rum]
              [cljs-http.client :as http]
              [clojure.string :refer [join split starts-with? trim]]
              [cljs-time.format :as fmt]
              ; dt is for datetime
              [cljs-time.core :as dt]
              [cljs.core.async :refer [<!]]
              [cljs.spec :as s])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    )

; (enable-console-print!)
; (println "This text is printed from src/scrape-n-mail/core.cljs.")

;; define your app data so that it doesn't get over-written on reload

(defonce app-data (atom {:initialized false
                         :signed-in? false
                         :sheet-data nil}))
(defonce wellbeing-sheet "1bv6vgW-HMTz0uhKDkrJoTg387b-WK6IFkFd9y6N96hA")

(def goog-datetime (fmt/formatter "MM/dd/YYYY HH:mm:ss"))

(defn parse-sheet [sheet-values]
  (map (fn [dt & rst] (into [(fmt/parse goog-datetime dt)] rst)) sheet-values) )

; This works - we need to deal with the fact that it's js!
; (map #(fmt/parse goog-datetime (aget % 0)) [first-row])

; We begin to describe what kind of information is in one record
; i.e., line in the spreadsheet

(s/def ::when-submitted dt/date?)
(s/def ::held-name-altar string?)
(s/def ::held-name-public string?)
(s/def ::procunciation-hints string?)
(s/def ::is-group-or-class boolean?)
; XXX This is copy-pasted validation. May need to examine to make sure it's good
(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(s/def ::submitter-email (s/and string? #(re-matches email-regex %)))
(s/def ::submitter-name string?)

(s/def ::held-person
  (s/keys :req [::when-submitted  ; a last-updated field would also be nice
                 ::held-name-altar ::held-name-public
                 ::is-group-or-class
                 ::submitter-email ::submitter-name]
           :opt [::procunciation-hints]))

(defn row-to-held-person [[timestamp name-for-altar _ pronunciation your-name your-email is-group? name-public]]
  (merge
    ; Our required fields
    {::when-submitted (fmt/parse goog-datetime timestamp)
     ::held-name-altar (trim name-for-altar)
     ::held-name-public (if (empty? name-public) (first (split name-for-altar #"\s+")) name-public) 
     ; We could make this more robust - but should be fine for now
     ::is-group-or-class (and (not (nil? is-group?)) (starts-with? is-group? "YES"))
     ; ::is-group-or-class is-group?
     ::submitter-email your-email
     ::submitter-name your-name}
    ; And the optional field
    (when-not (empty? pronunciation) {::procunciation-hints pronunciation}) ))

(defn get-wellbeing-data []
  (->
    (.get js/gapi.client.sheets.spreadsheets.values
          (clj->js {:spreadsheetId wellbeing-sheet
                    ; This may change as the form changes - even if the same kind of information remains
                    ; The way Svani is handling this now, we assume all currently visible names are being held
                    ; Retired names are sent to the archive sheet (this could be automated)
                    :range "Form Responses!A1:H"}) )
    ; values is the parsed version
    ; For now, we keep all the data - we can filter on the view
    ; We use rest to skip our header row
    (.then #(swap! app-data assoc :sheet-data (map row-to-held-person (rest (.. % -result -values))))) ))


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
(go (let [keys-resp (<! (http/get "keys.json"))]
      ; (println keys-resp)
      (if (:success keys-resp)
        (let [config-keys 
              (clj->js {:clientId "233834497336-aub2j24ggv45g1fs1ebe0470qnmsc38i.apps.googleusercontent.com",
                        :apiKey "AIzaSyCbU3ETLqdJ7VDon7dBjm5lOql9Nf6c5Yc"
                        :discoveryDocs ["https://sheets.googleapis.com/$discovery/rest?version=v4"]
                        :scope "https://www.googleapis.com/auth/spreadsheets.readonly"})
              ; XXX this is the source of the
              ; Error: conj on a map takes map entries or seqables of map entries
              ; Not sure why it breaks when doing advanced compilation, not figwheel...
              ; (->
              ;   {:discoveryDocs ["https://sheets.googleapis.com/$discovery/rest?version=v4"]
              ;    :scope "https://www.googleapis.com/auth/spreadsheets.readonly"}
              ;   (merge (:body keys-resp))
              ;   ; We definitely need this to undo the auto-converstion to EDN
              ;   ; that http/get does
              ;   clj->js )
              ]
          (.load js/gapi "client:auth2" #(init-client config-keys)) )
        #_(println keys-resp) )))


(rum/defc hello-world < rum/reactive []
  (let [curr-data (rum/react app-data)]
    [:div
      [:h2 "Wellness Scraper"]
      [:p "First, we authenticate you to Google, then get some data from the Wellenss spreadsheet"]
        (if (:initialized curr-data)
          (if-not (:signed-in? curr-data)
            [:button {:id "authorize-button" :on-click #(.. js/gapi.auth2 getAuthInstance signIn)} "Authorize"]
            [:button {:id "sign-out-button" :on-click #(.. js/gapi.auth2 getAuthInstance signOut)} "Sign Out"]) 
          [:p "Initializing Google API"])

        (when-let [records (:sheet-data curr-data)]
          (into [:ul {:id "content"}]
            ; We currently assume all visible data is "current"
            ; (filter #(s/includes? (aget % 0) "2017"))
            ; Each line is a list, this joins them in to one string
            (map #(vector :li (::held-name-altar %)) records))) ]))

(rum/mount (hello-world)
           (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)
