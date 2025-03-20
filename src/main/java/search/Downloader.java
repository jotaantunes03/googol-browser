package search;

import java.rmi.registry.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Downloader implements Runnable{
    private static IndexStorageBarrelInterface indexStorageBarrelInterface;
    private static URLQueueInterface urlQueueInterface;

    public Downloader() {
        try {
            // Conectar ao servidor (IndexStorageBarrel) RMI
            Registry registry = LocateRegistry.getRegistry(8183);
            indexStorageBarrelInterface = (IndexStorageBarrelInterface) registry.lookup("index");

            // Conectar ao servidor da Queue (URLQueue) RMI
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    public void run() {
        try {
            while (true) {
                String url = urlQueueInterface.takeUrl();  // Buscar uma URL da fila
                if (url == null) break; // Evita ficar num loop infinito

                System.out.println("Thread " + Thread.currentThread().getName() + " está processando: " + url);

                // Processa a URL, indexando-a
                if (!indexStorageBarrelInterface.isUrlIndexed(url)) {
                    System.out.println("Processando o url: " + url);
                    processUrl(url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    // Função para dividir palavras ligadas por pontuação
    private static String[] splitByPunctuation(String input) {
        return input.split("[\\p{Punct}&&[^-]]+"); // Mantém hífen, mas divide por outros sinais
    }

    // Remover apenas caracteres especiais, mantendo acentos
    private static String cleanWord(String input) {
        return input.replaceAll("[^\\p{L}-]", ""); // Mantém letras com acentos e hífen
    }

    // Remover os acentos
    private static String normalizeText(String inputWord) {
        return Normalizer.normalize(inputWord, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    // Verificar se a palavra contém pelo menos uma letra
    private static boolean containsLetter(String word) {
        return word.matches(".*[a-zA-Záéíóúâêîôûãõç].*"); // Retorna verdadeiro se houver pelo menos uma letra
    }

    // Função para verificar se uma palavra é um Link
    private static boolean isLink(String input) {
        String regex = "^(http|https|www)\\S+";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    private static void processUrl(String url) {
        try {

            // System.out.println("Processando o url: " + url);
            Document doc = Jsoup.connect(url).get();

            // Remover elementos desnecessários (scripts, estilos, menus)
            doc.select("script, style, nav, footer, header, aside").remove();
            // Extrair o texto e dividir em palavras
            String text = doc.body().text();


            for (String word: text.split("\\s+")) {
                if (word.isEmpty() || isLink(word)) continue;

                String[] splitWords = splitByPunctuation(word); // Dividir palavras ligadas por pontuação (penalties/penalties)

                for (String part : splitWords) {
                    part = cleanWord(part);  // Remover caracteres especiais mantendo os acentos
                    part = normalizeText(part); // Remover os acentos
                    part = part.toLowerCase(); // Converter para minúsculas

                    if (part.isEmpty() || !containsLetter(part)) continue;
                    indexStorageBarrelInterface.addToIndex(part, url);
                }
            }
            // Processar links dentro do documento
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









    public static void main(String[] args) {
        // Definir um pool de threads para downloaders
        int numThreads = 5;  // Número de threads a serem executadas simultaneamente (configurável)
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executorService.submit(new Downloader());
        }

        executorService.shutdown();
    }
}
