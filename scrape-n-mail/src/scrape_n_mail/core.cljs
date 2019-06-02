(ns scrape-n-mail.core
    (:require [rum.core :as rum]
              [cljs-http.client :as http]
              [clojure.string :refer [join split starts-with? trim lower-case]]
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
(defonce blank-form "https://goo.gl/forms/u9R2JEREElx89yOf2")
(defonce wellbeing-sheet "1bv6vgW-HMTz0uhKDkrJoTg387b-WK6IFkFd9y6N96hA")
(defonce payment-link "https://www.paypal.me/pools/c/85ChNpjaRV")

(def goog-datetime (fmt/formatter "MM/dd/YYYY HH:mm:ss"))

; this is not currently used
; (defn parse-sheet [sheet-values]
;  (map (fn [dt & rst] (into [(fmt/parse goog-datetime dt)] rst)) sheet-values) )

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

; this is a bit brittle - based on the structure of the form at some point in time
(defn row-to-held-person [[timestamp name-for-altar _ pronunciation your-name your-email is-group? name-public]]
  (merge
    (let [processed-group (and (not (nil? is-group?)) (starts-with? is-group? "YES"))]
      ; Our required fields
      ; I'm not actually using the timestamp now (just manually editing the
      ; spreadsheet) so this is rather unnecessary (and brittle)
      {::when-submitted timestamp  ; (fmt/parse goog-datetime timestamp)
       ::held-name-altar (trim name-for-altar)
       ; We could make this more robust - but should be fine for now
       ::is-group-or-class processed-group
       ; The logic is a little complex
       ; If we lack a public name, we propose one - if it's a group, we just
       ; use the full text, otherwise we use the first word of the altar name.
       ; We likely need a dictionary for stop-words like "Ms."
       ::held-name-public (if (empty? name-public) 
                            (if processed-group
                              name-for-altar
                              (first (split name-for-altar #"\s+")) )
                            name-public)
       ; ::is-group-or-class is-group?
       ::submitter-email (trim (lower-case your-email))
       ::submitter-name (trim your-name)} )
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

; TODO - make this generic so it only adds the fields that are specified
(defn prefilled-link [{name-altar ::held-name-altar
                       name-public ::held-name-public
                       pronunciation ::procunciation-hints
                       group ::is-group-or-class
                       submitter-name ::submitter-name
                       submitter-email ::submitter-email}]
  ; Format into links like this:
  ; https://docs.google.com/forms/d/e/1FAIpQLSc_FFrH7a_ClDmpAq36vA7gdUd1njmoEK0wfhRNaYcjfLox0w/viewform?usp=pp_url&entry.1145228181=name-for-altar&entry.2041965689=name-for-public&entry.1791240241=pronunciation-hints&entry.628312063=YES,+this+is+a+group&entry.552838120=your-name&entry.681476151=your-email 
  ; https://docs.google.com/forms/d/e/1FAIpQLSc_FFrH7a_ClDmpAq36vA7gdUd1njmoEK0wfhRNaYcjfLox0w/viewform?usp=pp_url&entry.1145228181&entry.2041965689&entry.1791240241&entry.552838120&entry.681476151
  (let [url-root 
        "https://docs.google.com/forms/d/e/1FAIpQLSc_FFrH7a_ClDmpAq36vA7gdUd1njmoEK0wfhRNaYcjfLox0w/viewform?usp=pp_url"
        custom-url
        (join \& [ url-root
                  (str "entry.1145228181=" name-altar)
                  (str "entry.2041965689=" name-public)
                  (str "entry.1791240241=" pronunciation)
                  (if group "entry.628312063=YES,+this+is+a+group")
                  (str "entry.552838120=" submitter-name)
                  (str "entry.681476151=" submitter-email)])]

    [:a {:href custom-url} name-altar]
  ))


(defn name-email-link [{submitter-name ::submitter-name
                        submitter-email ::submitter-email}]
  (let [url-root 
        "https://docs.google.com/forms/d/e/1FAIpQLSc_FFrH7a_ClDmpAq36vA7gdUd1njmoEK0wfhRNaYcjfLox0w/viewform?usp=pp_url"]
        (join \& [ url-root
                  (str "entry.552838120=" submitter-name)
                  (str "entry.681476151=" submitter-email)])
  ))


(defn email-text [submitter-email name-records]
  (let [personal-link (name-email-link (first name-records))]
    [
      [:hr]
      [:p submitter-email]
      [:br] 
      [:p "Dear " (::submitter-name (first name-records)) ","]

      [:p "Our general call for names for Wellbeing for June 2019 has gone out to the newDharma mailing list."]

      [:p "Currently, the community is holding the following names on the altar "
          "in support of their wellbeing at your request. Click "
          "directly on each name to get a pre-filled form for renewal. "
          "Please check each submission before clicking the submit button, and let Dav know "
          "if there is anything incorrect." ]

      (into [:ul {:id "content"}]
        ; We currently assume all visible data is "current"
        ; (filter #(s/includes? (aget % 0) "2017"))
        ; Each line is a list, this joins them in to one string
        (map #(vector :li (prefilled-link %)) name-records))

      [:p "New names may be submitted at " [:a {:href personal-link} "your presonal link"]
      ". If the above links don't work, you can copy-paste the form link into your browser:"]

      [:p personal-link]

      [:p [:em "As explained in the group email, it is traditional to offer Dana "
               "along with the submission of names. Here's "
               [:a {:href payment-link} "the link."]]]

      [:p "If we do not hear from you, we will remove the above names from the altar. J'ai mitra!"]

      ; Note that the \ is an escape character!
      [:p "/|\\"]
    ]))

(rum/defc scrape-it < rum/reactive []
  (let [curr-data (rum/react app-data)]
    [:div
      [:h2 "Wellness Scraper"]
      [:p "First, we authenticate you to Google, then get some data from " 
       [:a {:href "https://docs.google.com/spreadsheets/d/1bv6vgW-HMTz0uhKDkrJoTg387b-WK6IFkFd9y6N96hA/edit#gid=0"}
        "the Wellenss spreadsheet"]]
        (if (:initialized curr-data)
          (if-not (:signed-in? curr-data)
            [:button {:id "authorize-button" :on-click #(.. js/gapi.auth2 getAuthInstance signIn)} "Authorize"]
            [:button {:id "sign-out-button" :on-click #(.. js/gapi.auth2 getAuthInstance signOut)} "Sign Out"]) 
          [:p "Initializing Google API"])

        (when-let [records (:sheet-data curr-data)]
          (for [[submitter-email name-records] (group-by ::submitter-email records)]
            (email-text submitter-email name-records) ))
     ]))

(rum/mount (scrape-it)
           (. js/document (getElementById "app")))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)
