(ns erdos.oauth
  "OAuth client wrapper for Ring."
  (:require
   [org.httpkit.client :as client]
   [cheshire.core :as json]))

(declare request-user-info wrap-oauth)

(defn request->url [req]
  (str (-> req :scheme name) "://"
       (:server-name req)
       (if-let [p (:server-port req)] (str ":" p))
       (:uri req)))

(defn- request-matches-url? [url req]
  (= url (request->url req)))

(defn- redirect-to
  ([s] {:status 302, :headers {"Location" (str s)}})
  ([status s] {:status status, :headers {"Location" (str s)}}))


(defn- query-string->map [query-string]
  (if (.contains (str query-string) "=")
    (into {} (for [x (.split (str query-string) "&")
                   :let [[k v] (.split (str x) "=" 2)]]
               [(java.net.URLDecoder/decode k "UTF-8")
                (java.net.URLDecoder/decode v "UTF-8")]))))


(defn- build-url [url opts]
  (let [url   (str url)
        b     (new StringBuilder url)
        ->str (fn [a] (if (keyword? a) (name a) (str a)))]
    (when (seq opts)
      (reduce-kv
       (fn [_ k v]
         (.append b \&)
         (.append b (java.net.URLEncoder/encode (->str k) "UTF-8"))
         (.append b \=)
         (.append b (java.net.URLEncoder/encode (->str v) "UTF-8")))
       nil opts)
      (.setCharAt b (.length url) \?))
    (.toString b)))


(defn- assoc-if-val [m & kvs]
  (apply (fn f
           ([m] (persistent! m))
           ([m k v & kvs] (apply f (if (some? v) (assoc! m k v) m) kvs)))
         (transient (or m {})) kvs))


(defn- do-callback [response-value callback-async? response-fn raise-fn callback]
  (assert (contains? #{true false} callback-async?))
  (assert (fn? response-fn))
  (assert (fn? raise-fn))
  (assert (fn? callback))
  (if callback-async?
    (callback response-value response-fn raise-fn)
    (try (response-fn (callback response-value))
         (catch Throwable t (raise-fn t)))))


(defn- handle-oauth-error [params request response raise opts]
  (do-callback (assoc request :oauth-error (get params "error"))
               (:callback-async? opts)
               response raise
               (:error opts)))


(defn- handle-oauth-code [params request response raise
                          {:as opts :keys [error success callback-async?]}]
  (let [m {"grant_type"    "authorization_code"
           "code"          (get params "code")
           "redirect_uri"  (:url opts)
           "client_id"     (:id opts)
           "client_secret" (:secret opts)}]
    (client/post (:url-exchange opts)
                 {:form-params m}
                 (fn [resp]
                   (try
                     (let [obj (-> resp :body json/parse-string)]
                       ;; code is maybe expired, etc.
                       (if-let [err (get obj "error")]
                         (-> request
                             (assoc :oauth-error
                                    {:error err
                                     :state (get params "state")})
                             (do-callback callback-async? response raise error))
                         (let [request
                               (update request :oauth-success assoc-if-val
                                       :access-token  (get obj "access_token")
                                       :expires-in    (get obj "expires_in")
                                       :token-type    (get obj "token_type") ;; always Bearer!
                                       :refresh-token (get obj "refresh_token")
                                       :state         (get params "state"))]
                           (if-let [user-info-method
                                    (get-method request-user-info (some-> opts :service name .toLowerCase))]
                             (user-info-method
                              (get obj "access_token")
                              (fn ui-success [user-info]
                                (-> request
                                    (assoc-in [:oauth-success :user-info] user-info)
                                    (do-callback callback-async? response raise success)))
                              (fn ui-error [err]
                                (-> request
                                    (assoc-in [:oauth-error {:user-info err
                                                             :state     (get params "state")}])
                                    (do-callback callback-async? response raise error)))
                              opts)
                             (do-callback request callback-async? response raise success)))))
                     (catch Throwable t (raise t)))))))


(defn- handle-oauth-default
  "Redirects to the oauth authentication endpoint"
  [params request response raise opts]
  (->> (:endpoint-params opts)
       (into {:client_id     (:id opts)
              :redirect_uri  (:url opts)
              :response_type :code
              :state (get params "state")})
       (build-url (:url-endpoint opts))
       (redirect-to 307)
       (response)))


(defn handle-oauth
  "Arguments:
  First argument is request map. Second argument is map of keys:
  - :url-endpoint - OAuth 2.0 endpoint of provider
  - :url-exchange - Code to token exchange endpoint of provider
  - :id           - Client id
  - :secret       - Client secret
  - :url          - Local url for login
  - :success      - Asyncronous ring handler fn called on success.
  - :error        - Asyncronous ring handler fn called on error."
  [request response raise {:as opts :keys [error success callback-async?]}]
  (assert (request-matches-url? (:url opts) request))
  (assert (string? (:url-endpoint opts)))
  (assert (string? (:url-exchange opts)))
  (assert (string? (:id opts)))
  (assert (string? (:secret opts)))
  (assert (string? (:url opts)))
  (assert (fn? success))
  (assert (fn? error))
  (assert (contains? #{true false} callback-async?))
  (let [query-params (-> request :query-string query-string->map)]
    (cond
      ;; error during login:
      (contains? query-params "error")
      (handle-oauth-error query-params request response raise opts)
      ;; successfully redirected back:
      (contains? query-params "code")
      (handle-oauth-code query-params request response raise opts)
      ;; redirect to provider:
      :default
      (handle-oauth-default query-params request response raise opts))))


(defn- ->handler-fn
  "Creates an error/success handler fn from object."
  [x]
  (assert (some? x) "Function not given!")
  (cond
    (fn? x) x
    (map? x) (fn ([req resp err] (resp x)) ([_] x))
    (instance? java.net.URL x)
    (fn
      ([_ resp _] (resp (redirect-to x)))
      ([_] (redirect-to x)))
    (instance? java.net.URI x)
    (fn
      ([_ resp _] (resp (redirect-to x)))
      ([_] (redirect-to x)))
    :else
    (throw (IllegalArgumentException.
           (str "Can not create fn from " (type x))))))


(defmulti wrap-oauth (fn [_ & {s :service}] (some-> s name .toLowerCase)))

(defn wrap-oauth-default [handler {:as opts :keys [id secret url]}]
  (let [opts (-> opts
                 (update :error (fnil ->handler-fn handler))
                 (update :success (fnil ->handler-fn handler)))]
    (fn
      ([request]
       (if (request-matches-url? url request)
         (let [p (promise)]
           (handle-oauth request
                         (partial deliver p) (partial deliver p)
                         (update opts :callback-async? (fnil boolean false)))
           (if (instance? Throwable @p) (throw @p) @p))
         (handler request)))
      ([request response raise]
       (if (request-matches-url? url request)
         (handle-oauth request
                       response raise
                       (update opts :callback-async? (fnil boolean true)))
         (handler request response raise))))))


(defmethod wrap-oauth nil [handler & {:as opts}]
  (wrap-oauth-default handler opts))


;; asyncronous way to get user info from auth token
(defmulti request-user-info (fn [token success error opts] (some-> opts :service name .toLowerCase)))


                                        ; GOOGLE


(defmethod request-user-info "google" [token callback-fn error-fn _]
  (client/get
   "https://www.googleapis.com/oauth2/v1/userinfo"
   {:headers {"Authorization" (str "Bearer " token)}}
   #(as-> (json/parse-string (:body %)) *
      (assoc * :id (get * "id"))
      (assoc * :name (get * "name"))
      (callback-fn *)
      (try * (catch Throwable t (error-fn t))))))


(defmethod wrap-oauth "google"
  #_
  "See:

  - https://console.developers.google.com/?pli=1
  - https://developers.google.com/youtube/v3/guides/auth/server-side-web-apps#Obtaining_Access_Tokens"
  [handler & {:keys [url success error id secret scopes]}]
  (assert (coll? scopes) "No :scopes given.")
  (wrap-oauth-default
   handler
   {:url             url
    :id              id
    :secret          secret
    :service         :google
    :url-endpoint    "https://accounts.google.com/o/oauth2/auth"
    :url-exchange    "https://accounts.google.com/o/oauth2/token"
    :success         success
    :error           error
    :endpoint-params {:scope (clojure.string/join " " scopes)
                      :access_type "offline"}}))


                                        ; FACEBOOK


(defmethod request-user-info "facebook" [token callback-fn error-fn _]
  (client/get
   "https://graph.facebook.com/me"
   {:query-params {:access_token token}}
   #(as-> (json/parse-string (:body %)) *
      (assoc * :id (get * "id"))
      (assoc * :name (get * "name"))
      (callback-fn *)
      (try * (catch Throwable t (error-fn t))))))


(defmethod wrap-oauth "facebook"
  [handler & {:keys [url success error id secret scopes]}]
  (assert (coll? scopes) "No :scopes given.")
  (wrap-oauth-default
   handler
   {:url             url
    :id              id
    :secret          secret
    :service         :facebook
    :url-endpoint    "https://www.facebook.com/dialog/oauth"
    :url-exchange    "https://graph.facebook.com/v2.3/oauth/access_token"
    :success         success
    :error           error
    :endpoint-params {:scope (clojure.string/join " " scopes)
                      :response_type "code"}}))


                                        ; LINKEDIN


(defmethod wrap-oauth "linkedin"
  #_
  "Wrapper for LinkedIn authentication. For configuration, see:
  - https://www.linkedin.com/secure/developer
  - https://developer.linkedin.com/docs/oauth2"
  [handler & {:keys [url success error id secret]}]
  (let []
    (wrap-oauth-default
     handler
     {:url          url
      :id           id
      :secret       secret
      :url-endpoint "https://www.linkedin.com/oauth/v2/authorization"
      :url-exchange "https://www.linkedin.com/oauth/v2/accessToken"
      :success      success
      :error        error})))

;; generating wrap-oauth-* functions

(defmacro defwrapper [& ms]
  (cons 'do (for [m ms]
              `(defn ~(symbol (str "wrap-oauth-" (name m))) [h# ~'& opts#]
                 (apply wrap-oauth h# :service ~m opts#)))))

(defwrapper :facebook :google :linkedin)


'OK
