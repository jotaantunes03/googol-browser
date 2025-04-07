cd target/
java -cp "./lib/jsoup-1.18.3.jar:./lib/sqlite-jdbc-3.49.1.0.jar:." search.URLQueue
java -cp "./lib/jsoup-1.18.3.jar:./lib/sqlite-jdbc-3.49.1.0.jar:." search.IndexStorageBarrel 8182 server1
java -cp "./lib/jsoup-1.18.3.jar:." search.Gateway
java -cp "./lib/jsoup-1.18.3.jar:." search.GoogolClient
java -cp "./lib/jsoup-1.18.3.jar:." search.Downloader

