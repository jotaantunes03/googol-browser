package search;

import java.rmi.registry.*;
import java.text.Normalizer;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

public class Downloader {
    private static IndexStorageBarrelInterface indexStorageBarrelInterface;
    private static URLQueueInterface urlQueueInterface;

    // Número máximo de threads a usar para o processamento paralelo:
    private static final int NUM_THREADS = 5;
    private static ExecutorService executor;

    public static void main(String[] args) {
        try {
            // Conectar ao servidor (IndexStorageBarrel) RMI
            Registry registry = LocateRegistry.getRegistry(8183);
            indexStorageBarrelInterface = (IndexStorageBarrelInterface) registry.lookup("index");

            // Conectar ao servidor da Queue (URLQueue) RMI:
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");

            // Criar um executor com um número fixo de threads
            executor = Executors.newFixedThreadPool(NUM_THREADS);

            // Criar uma fila bloqueante para distribuir tarefas às threads
            BlockingQueue<String> urlQueue = new LinkedBlockingQueue<>();

            // Iniciar um thread separado para buscar URLs constantemente
            new Thread(() -> {
                try {
                    while (true) {
                        String url = urlQueueInterface.takeUrl();
                        if (url != null && !indexStorageBarrelInterface.isUrlIndexed(url)) {
                            urlQueue.put(url); // Adicionar URL à fila bloqueante
                        } else {
                            Thread.sleep(1000); // Esperar antes de verificar novamente
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Criar workers que ficam constantemente a processar URLs da fila
            for (int i = 0; i < NUM_THREADS; i++) {
                executor.execute(() -> {
                    while (true) {
                        try {
                            String url = urlQueue.take(); // Espera até haver um URL disponível
                            processUrl(url);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void processUrl(String url) {
        try {
            System.out.println("Processando o URL: " + url);
            Document doc = Jsoup.connect(url).get();

            // Remover elementos desnecessários (scripts, estilos, menus)
            doc.select("script, style, nav, footer, header, aside").remove();
            String text = doc.body().text();

            for (String word : text.split("\\s+")) {
                if (word.isEmpty() || isLink(word)) continue;

                String cleanedWord = normalizeText(cleanWord(word.toLowerCase()));

                if (containsLetter(cleanedWord)) {
                    indexStorageBarrelInterface.addToIndex(cleanedWord, url);
                }
            }

            processLinks(doc, url);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void processLinks(Document document, String sourceUrl) {
        try {
            Elements links = document.select("a[href]");
            for (Element link : links) {
                String absUrl = link.attr("abs:href");
                if (!absUrl.isEmpty()) {
                    urlQueueInterface.addUrl(absUrl);
                    indexStorageBarrelInterface.addLink(sourceUrl, absUrl);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isLink(String input) {
        String regex = "^(http|https|www)\\S+";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    private static String cleanWord(String input) {
        return input.replaceAll("[^\\p{L}-]", "");
    }

    private static String normalizeText(String inputWord) {
        return Normalizer.normalize(inputWord, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static boolean containsLetter(String word) {
        return word.matches(".*[a-zA-Záéíóúâêîôûãõç].*");
    }
}
