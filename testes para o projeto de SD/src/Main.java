import java.rmi.RemoteException;
import java.text.Normalizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class Main {
    private ConcurrentHashMap<String, CopyOnWriteArraySet<String>> linksGraph;

    public Main() {
        linksGraph = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) {
        /*
        String input = "Oláa!";
        String processedWord;
        processedWord = normalizeText(input);
        processedWord = lowerCase(processedWord);
        processedWord = cleanWord(processedWord);
        System.out.println(processedWord);
         */


        Main test = new Main();

        // Exemplo de entrada
        test.addLink("https://www.wikipedia.org/", "https://www.google.com/");
        test.addLink("https://www.wikipedia.org/", "https://www.bbc.com/");
        test.addLink("https://www.google.com/", "https://www.bbc.com/");

        // Imprimir o gráfico de links
        test.printLinksGraph();

    }


    // Função para retirar os acentos das palavras:
    private static String normalizeText(String inputWord) {
        return Normalizer.normalize(inputWord, Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
    }

    // Função para colocar as palavras todas em letras minúsculas:
    private static String lowerCase(String inputWord) {
        return inputWord.toLowerCase();
    }

    // Função para remover caracteres não alfabéticos no início ou no fim da palavra
    private static String cleanWord(String input) {
        return input.replaceAll("^[^a-zA-Z]+|[^a-zA-Z]+$", "");
    }

    public void addLink(String sourceUrl, String linkedUrl){
        linksGraph.computeIfAbsent(sourceUrl, k -> new CopyOnWriteArraySet<>()).add(linkedUrl);
    }

    // Função para imprimir o gráfico de links
    public void printLinksGraph() {
        for (String source : linksGraph.keySet()) {
            System.out.println("Source: " + source);
            for (String link : linksGraph.get(source)) {
                System.out.println("  Links to: " + link);
            }
        }
    }
}