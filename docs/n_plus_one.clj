(ns n-plus-one)

(require '[promesa.core :as prom])
(require '[urania.core :as u])

;; db
(require '[datascript.core :as d])

(def schema
  {:user {:user/id {:db/cardinality :db.cardinality/one}
          :user/name {:db/cardinality :db.cardinality/one}}
   :post {:post/id {:db/cardinality :db.cardinality/one}
          :post/user {:db/cardinality :db.cardinality/one}
          :post/title {:db/cardinality :db.cardinality/one}
          :post/text {:db/cardinality :db.cardinality/one}}})

(def db (d/create-conn schema))

;; add users
(d/transact! db [{:user/name "Ada"
                  :user/id 1}
                 {:user/name "Bob"
                  :user/id 2}
                 {:user/name "Claire"
                  :user/id 3}
                 {:user/name "Diana"
                  :user/id 4}
                 {:user/name "Edsger"
                  :user/id 5}
                 {:user/name "Frank"
                  :user/id 6}])

;; add posts
(doseq [user-id [1 2 3 4 5 6]
        post-id (take 10 (iterate inc (* 100 user-id)))]
  (d/transact! db [{:post/id post-id
                    :post/user user-id
                    :post/title (str  "A post with id #" post-id)
                    :post/text ""}]))

;; -- posts

(def posts-query
  '[:find [?e ...]
    :where [?e :post/id ?id]])

(defn get-post-ids [limit]
  (take limit (d/q posts-query @db)))

(defn pull-posts [limit]
  (d/pull-many @db '[*] (get-post-ids limit)))

(defrecord Posts [limit]
  u/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve reject]
      (println "Fetching " limit " post(s)")
      (resolve (pull-posts limit)))))

  u/LabeledSource
  (resource-id [_] limit))


(defn get-posts [limit]
  (Posts. limit))

(comment
  (u/run!! (get-posts 10))
  (u/run!! (u/fmap count (get-posts 10)))
  (u/run!! (u/fmap #(map :post/id %) (get-posts 10))))

;; -- user

(defn user-query [id]
  `[:find [?e]
    :where [?e :user/id ~id]])

(def users-query
  '[:find [?e ...]
    :in $ ?wanted
    :where [?e :user/id ?id]
           [(?wanted ?id)]])

(defn get-user-by-id [id]
  (first (d/q (user-query id) @db)))

(defn pull-user [id]
  (d/pull @db '[*] (get-user-by-id id)))

(defn pull-users [ids]
  (d/pull-many @db
               '[*]
               (d/q users-query @db (set ids))))

(defrecord User [id]
  u/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve reject]
       (println "Fetching User #" id)
       (resolve (pull-user id)))))

  u/BatchedSource
  (fetch-multi [_ users]
    (prom/promise
     (fn [resolve reject]
       (let [all-ids (into #{id} (map :id users))
             users (pull-users all-ids)
             ids (map :user/id users)]
         (println "Fetching Users " all-ids)
         (resolve (zipmap ids users)))))))

(defn get-user [id]
  (User. id))

(comment
  (u/run!! (get-user 1))
  (u/run!! (get-user 2))
  (u/run!! (get-user 3))
  (u/run!! (get-user 4)))

(comment
  ;; caching
  (u/run!! (u/collect [(get-user 1) (get-user 1) (get-user 2)])))

;; -- putting it all together

(defn attach-author [{user :post/user :as post}]
  (u/fmap #(assoc post :post/user %)
          (get-user user)))

(defn latest-posts [n]
  (u/traverse attach-author
             (get-posts n)))

(comment
  (u/run!! (latest-posts 10)))
