(ns n-plus-one)

(require '[promissum.core :as prom])
(require '[muse.core :as muse])

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
  muse/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve]
      (println "Fetching " limit " post(s)")
      (resolve (pull-posts limit)))))

  muse/LabeledSource
  (resource-id [_] limit))


(defn get-posts [limit]
  (Posts. limit))

(comment
  (muse/run!! (get-posts 10))
  (muse/run!! (muse/fmap count (get-posts 10)))
  (muse/run!! (muse/fmap #(map :post/id %) (get-posts 10))))

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
  muse/DataSource
  (fetch [_]
    (prom/promise
     (fn [resolve]
       (println "Fetching User #" id)
       (resolve (pull-user id)))))

  muse/BatchedSource
  (fetch-multi [_ users]
    (prom/promise
     (fn [resolve]
       (let [all-ids (into #{id} (map :id users))
             users (pull-users all-ids)
             ids (map :user/id users)]
         (println "Fetching Users " all-ids)
         (resolve (zipmap ids users)))))))

(defn get-user [id]
  (User. id))

(comment
  (muse/run!! (get-user 1))
  (muse/run!! (get-user 2))
  (muse/run!! (get-user 3))
  (muse/run!! (get-user 4)))

(comment
  ;; caching
  (muse/run!! (muse/collect [(get-user 1) (get-user 1) (get-user 2)])))

;; -- putting it all together

(defn attach-author [{user :post/user :as post}]
  (muse/fmap #(assoc post :post/user %)
             (get-user user)))

(defn latest-posts [n]
  (muse/traverse attach-author
                 (get-posts n)))

(comment
  (muse/run!! (latest-posts 10)))
