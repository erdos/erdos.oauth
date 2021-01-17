(defproject io.github.erdos/erdos.oauth "0.1.6"
  :description "OAuth library for Ring in Clojure"
  :url "https://github.com/erdos/erdos.oauth"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [http-kit "2.5.1"]
                 [cheshire "5.10.0"]]
  :repositories [["clojars" {:url "https://clojars.org/repo"
                             :username :env/clojars_user
                             :password :env/clojars_pass
                             :sign-releases false}]])
