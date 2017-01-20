(ns erdos.oauth-demo
  (:require [erdos.oauth]
            [org.httpkit.server]))

;; these values should be according to your app settings

(def config-map (read-string (slurp "/tmp/google-auth-config")))

(def google-app-id (:app-id config-map))
(def google-app-secret (:app-secret config-map))
(def login-url (:url config-map))


(defn default-handler [request]
  (if-let [err (-> request :oauth-error)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str "login error: " (pr-str err))}
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (format "Click <a href=\"%s?state=login+action\">here</a> to log in." login-url)}))

(defn on-success [request]
  ;; we should save the user id to session variables and redirect
  {:status 200
   :body (str "Success! You are: "
              (-> request :oauth-success :user-info :name)
              " with id "
              (-> request :oauth-success :user-info :id))})

(def wrapped-handler
  (->
   default-handler
   (erdos.oauth/wrap-oauth-google
    :url     login-url
    :id      google-app-id
    :secret  google-app-secret
    :success on-success
    ;:error   on-error
    :scopes  ["https://www.googleapis.com/auth/analytics.readonly"
              "https://www.googleapis.com/auth/userinfo.email"
              "https://www.googleapis.com/auth/userinfo.profile"])))

(defn main []
  (let [s (org.httpkit.server/run-server
           wrapped-handler {:port 8000})]
    (Thread/sleep 60000) ;; server is alive for 1 minute
    (s)))

;;(main)
