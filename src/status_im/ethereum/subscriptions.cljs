(ns status-im.ethereum.subscriptions
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [status-im.constants :as constants]
            [status-im.ethereum.transactions.core :as transactions]
            [status-im.native-module.core :as status]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.utils.fx :as fx]
            [taoensso.timbre :as log]))

(fx/defn handle-signal
  [cofx {:keys [subscription_id data] :as event}]
  (if-let [handler (get-in cofx [:db :ethereum/subscriptions subscription_id])]
    (handler data)
    (log/warn ::unknown-subscription :event event)))

(fx/defn handle-error
  [cofx {:keys [subscription_id data] :as event}]
  (log/error ::error event))

(fx/defn register-subscription
  [{:keys [db]} id handler]
  {:db (assoc-in db [:ethereum/subscriptions id] handler)})

(fx/defn new-block
  [{:keys [db] :as cofx} {:keys [number transactions] :as block}]
  (when number
    (let [{:keys [:account/account :wallet/all-tokens network]} db
          chain (ethereum/network->chain-keyword (get-in account [:networks network]))
          chain-tokens (into {} (map (juxt :address identity)
                                     (tokens/tokens-for all-tokens chain)))
          wallet-address (ethereum/normalized-address (:address account))]
      {:db (assoc-in db [:ethereum/current-block] number)
       :ethereum.transactions/enrich-transactions-from-new-blocks
       {:chain-tokens chain-tokens
        :block block
        :transactions (filter (fn [{:keys [to from]}]
                                (or (= wallet-address to)
                                    (= wallet-address from)))
                              transactions)}})))

(defn subscribe-signal
  [filter params callback]
  (let [args (.stringify js/JSON (clj->js {:jsonrpc "2.0"
                                           :id      1
                                           :method  "eth_subscribeSignal"
                                           :params  [filter params]}))]
    (status/call-private-rpc
     args
     (fn [response]
       (if (string/blank? response)
         (log/error ::subscription-unknown-error :filter filter :params params)
         (let [{:keys [error result]}
               (-> (.parse js/JSON response)
                   (js->clj :keywordize-keys true))]
           (if error
             (log/error ::subscription-error error :filter filter :params params)
             (re-frame/dispatch [:ethereum.callback/subscription-success
                                 result
                                 callback]))))))))

(defn new-token-transaction-filter
  [{:keys [chain-tokens from to] :as args}]
  (subscribe-signal
   "eth_newFilter"
   [{:fromBlock "latest"
     :toBlock "latest"
     :address (keys chain-tokens)
     :topics [constants/event-transfer-hash from to]}]
   (transactions/inbound-token-transfer-handler chain-tokens)))

(re-frame/reg-fx
 :ethereum.subscriptions/token-transactions
 (fn [{:keys [address] :as args}]
   ;; start inbound token transaction subscriptions
   ;; outbound token transactions are already caught in new blocks filter
   (new-token-transaction-filter (merge args
                                        {:direction :inbound
                                         :to address}))))

(defn new-block-filter
  []
  (subscribe-signal
   "eth_newBlockFilter" []
   (fn [[block-hash]]
     (transactions/get-block-by-hash
      block-hash
      (fn [block]
        (re-frame/dispatch [:ethereum.signal/new-block block]))))))

(re-frame/reg-fx
 :ethereum.subscriptions/new-block
 new-block-filter)

(fx/defn initialize
  [{:keys [db] :as cofx}]
  (let [{:keys [:account/account :wallet/all-tokens network]} db
        chain (ethereum/network->chain-keyword (get-in account [:networks network]))
        chain-tokens (into {} (map (juxt :address identity)
                                   (tokens/tokens-for all-tokens chain)))
        normalized-address (ethereum/normalized-address (:address account))
        padded-address (transactions/add-padding normalized-address)]
    {:ethereum.subscriptions/new-block nil
     :ethereum.subscriptions/token-transactions
     {:chain-tokens chain-tokens
      :address padded-address}}))
