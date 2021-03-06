(ns clojure-lsp.main
  (:require [clojure-lsp.parser :as parser]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.core.async :as async])
  (:import (org.eclipse.lsp4j.services LanguageServer TextDocumentService WorkspaceService)
           (org.eclipse.lsp4j InitializedParams InitializeParams InitializeResult ServerCapabilities CompletionOptions DidOpenTextDocumentParams DidChangeTextDocumentParams DidSaveTextDocumentParams DidCloseTextDocumentParams TextDocumentPositionParams CompletionItem TextEdit Range Position DidChangeConfigurationParams DidChangeWatchedFilesParams TextDocumentSyncOptions TextDocumentSyncKind SaveOptions CompletionItemKind)
           (org.eclipse.lsp4j.launch LSPLauncher)
           (java.util.concurrent CompletableFuture)
           (java.util.function Supplier)))

(defonce db (atom {:documents {}}))

(defn- save-document [uri text]
  (swap! db assoc-in [:documents uri] {:v 0 :text text})
  text)

(deftype LSPTextDocumentService []
  TextDocumentService
  (^void didOpen [this ^DidOpenTextDocumentParams params]
    (log/warn params)
    (let [document (.getTextDocument params)]
      (save-document (.getUri document) (.getText document))))

  (^void didChange [this ^DidChangeTextDocumentParams params]
    (log/warn params)
    (let [textDocument (.getTextDocument params)
          version (.getVersion textDocument)
          changes (.getContentChanges params)
          text (.getText (.get changes 0))]
      (loop [state-db @db]
        (when (> version (get-in state-db [:documents (.getUri textDocument) :v] -1))
          (when-not (compare-and-set! db state-db (assoc-in state-db [:documents (.getUri textDocument)] {:v version :text text}))
            (recur @db))))))

  (^void didSave [this ^DidSaveTextDocumentParams params]
    (log/warn params))
  (^void didClose [this ^DidCloseTextDocumentParams params]
    (log/warn params))

  (^CompletableFuture completion [this ^TextDocumentPositionParams params]
    (log/warn params)
    (CompletableFuture/supplyAsync
      (reify Supplier
        (get [this]
          (try
            (let [pos (.getPosition params)
                  doc-id (.getUri (.getTextDocument params))
                  text (or (get-in @db [:documents doc-id :text])
                           (slurp doc-id))
                  parsed (parser/parse text
                                       (inc (.getLine pos))
                                       (inc (.getCharacter pos)))
                  env (first parsed)
                  syms (concat (:scoped env)
                               (:publics env)
                               (keys (:refers env))
                               (vals (:aliases env))
                               (:requires env))
                  file-envs (:file-envs @db)
                  {:keys [add-require? line column]} (:require-pos env)]
              (into
                (set (mapv (fn [sym] (CompletionItem. (name sym))) syms))
                (mapcat (fn [[doc-id file-env]]
                          (let [ns-sym (:ns file-env)
                                file-alias (get-in env [:aliases ns-sym])
                                alias (get-in @db [:project-aliases ns-sym])
                                as-alias (cond-> ""
                                           alias (str " :as " (name alias)))
                                ref (or file-alias alias ns-sym)]
                            (mapv #(doto (CompletionItem. (format "%s/%s" (name ref) (name %)))
                                     (.setAdditionalTextEdits
                                       (cond-> []
                                         (not (contains? (:requires env) ns-sym))
                                         (conj (TextEdit. (Range. (Position. (dec line) (dec column))
                                                                  (Position. (dec line) (dec column)))
                                                          (if add-require?
                                                            (format "\n  (:require\n   [%s%s])" (name ns-sym) as-alias)
                                                            (format "\n   [%s%s]" (name ns-sym) as-alias)))))))
                                  (:publics file-env))))
                        file-envs)))
            (catch Exception e
              (log/error e))))))))


(deftype LSPWorkspaceService []
  WorkspaceService
  (^void didChangeConfiguration [this ^DidChangeConfigurationParams params]
    (log/warn params))
  (^void didChangeWatchedFiles [this ^DidChangeWatchedFilesParams params]
    (log/warn params)))

(defn crawl-files [files]
  (let [xf (comp (filter #(.isFile %))
                 (map #(.getAbsolutePath %))
                 (filter (fn [path]
                           (or (string/ends-with? path ".clj")
                               (string/ends-with? path ".cljc"))))
                 (map (juxt identity (comp parser/find-publics slurp))))
        output-chan (async/chan)]
    (async/pipeline-blocking 5 output-chan xf (async/to-chan files))
    (async/<!! (async/into {} output-chan))))

(defrecord LSPServer []
  LanguageServer
  (^CompletableFuture initialize [this ^InitializeParams params]
    (when-let [project-root (.getRootUri params)]
      (let [root-file (io/file (string/replace-first project-root "file://" "") "src")
            file-envs (->> (file-seq root-file)
                           (crawl-files))]
        (swap! db assoc
               :file-envs file-envs
               :project-aliases (apply merge (map (comp :aliases val) file-envs)))))

    (CompletableFuture/completedFuture
      (InitializeResult. (doto (ServerCapabilities.)
                           (.setTextDocumentSync (doto (TextDocumentSyncOptions.)
                                                   (.setChange TextDocumentSyncKind/Full)
                                                   (.setSave (SaveOptions. true))))
                           (.setCompletionProvider (CompletionOptions. false [\c]))))))
  (^void initialized [this ^InitializedParams params]
    (log/warn "HELLO"))
  (^CompletableFuture shutdown [this]
    (log/warn "bye")
    (CompletableFuture/completedFuture
      {:result nil}))
  (exit [this]
    (System/exit 1))
  (getTextDocumentService [this]
    (LSPTextDocumentService.))
  (getWorkspaceService [this]
    (LSPWorkspaceService.)))

(defn -main [& args]
  (let [server (LSPServer.)
        launcher (LSPLauncher/createServerLauncher server System/in System/out)]
    (swap! db assoc :client (.getRemoteProxy launcher))
    (.startListening launcher)))
