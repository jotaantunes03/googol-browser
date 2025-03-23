package search;

import java.rmi.registry.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import search.Sockets.ReliableMulticast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Downloader class is responsible for retrieving web content, processing it,
 * and extracting relevant data for indexing. It implements the Runnable interface
 * to enable concurrent processing of multiple URLs simultaneously.
 *
 * <p>This class performs the following key functions:</p>
 * <ul>
 *   <li>Fetches URLs from a distributed queue</li>
 *   <li>Downloads and parses web page content</li>
 *   <li>Extracts and normalizes words for indexing</li>
 *   <li>Transmits index data via reliable multicast</li>
 *   <li>Processes and extracts links from web pages</li>
 * </ul>
 *
 * <p>The class uses RMI (Remote Method Invocation) to communicate with a URL queue
 * and multicast sockets to distribute the processed data to storage barrels.</p>
 *
 * @author João Antunes and David Cameijo
 */
public class Downloader implements Runnable {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Interface for accessing the index storage barrel through RMI */
    private static IndexStorageBarrelInterface indexStorageBarrelInterface;

    /** Interface for accessing the URL queue through RMI */
    private static URLQueueInterface urlQueueInterface;

    /** The multicast group address for distributed communication */
    private static final String GROUP_ADDRESS = "230.0.0.0";

    /** The port number for multicast communication */
    private static final int PORT = 4446;

    /** Reliable multicast instance for sending processed data */
    private static ReliableMulticast multicast;

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new Downloader instance, initializing the RMI connections
     * and multicast communication infrastructure.
     *
     * <p>The constructor establishes a connection to the URL queue service via RMI
     * and initializes the reliable multicast communication channel.</p>
     */
    public Downloader() {
        try {
            // Initialize the reliable multicast communication infrastructure
            multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);

            // Connect to the URL Queue service via RMI
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * The main execution method that processes URLs retrieved from the queue.
     * Implements the Runnable interface to allow concurrent execution.
     *
     * <p>This method continuously retrieves URLs from the queue and processes them
     * until there are no more URLs available or an exception occurs.</p>
     */
    public void run() {
        try {
            while (true) {
                // Retrieve a URL from the distributed queue
                String url = urlQueueInterface.takeUrl();

                // Exit the loop if no more URLs are available
                if (url == null) break;

                System.out.println("Thread " + Thread.currentThread().getName() + " está processando: " + url);

                /*
                // Checks if URL has already been indexed (commented out in original code)
                if (!indexStorageBarrelInterface.isUrlIndexed(url)) {
                    System.out.println("Processando o url: " + url);
                    processUrl(url);
                }
                */

                // Process the URL to extract and index its content
                processUrl(url);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Splits a string into components based on punctuation characters.
     *
     * <p>This method divides a text string whenever punctuation is encountered,
     * with the exception of hyphens which are preserved. This is useful for
     * tokenizing text while preserving hyphenated words.</p>
     *
     * @param input The string to be divided
     * @return An array of strings split by punctuation
     */
    private static String[] splitByPunctuation(String input) {
        // Split by all punctuation except hyphens
        return input.split("[\\p{Punct}&&[^-]]+");
    }

    /**
     * Removes special characters from a word while preserving letters and hyphens.
     *
     * <p>This method sanitizes text by removing everything except letters and hyphens,
     * preserving accented characters and other Unicode letters.</p>
     *
     * @param input The string to be cleaned
     * @return The cleaned string containing only letters and hyphens
     */
    private static String cleanWord(String input) {
        // Keep only letters (including accented ones) and hyphens
        return input.replaceAll("[^\\p{L}-]", "");
    }

    /**
     * Normalizes text by removing diacritical marks (accents).
     *
     * <p>This method transforms accented characters to their base form by
     * first decomposing them into base characters and combining marks,
     * then removing the combining marks.</p>
     *
     * @param inputWord The word to be normalized
     * @return The normalized word without diacritical marks
     */
    private static String normalizeText(String inputWord) {
        // Decompose accented characters and remove the accent marks
        return Normalizer.normalize(inputWord, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Checks if a string contains at least one alphabetic character.
     *
     * <p>This method verifies that a string contains at least one letter,
     * including ASCII and common accented Latin characters.</p>
     *
     * @param word The string to check
     * @return true if the string contains at least one letter, false otherwise
     */
    private static boolean containsLetter(String word) {
        // Check if the word contains at least one letter (including accented chars)
        return word.matches(".*[a-zA-Záéíóúâêîôûãõç].*");
    }

    /**
     * Determines if a string appears to be a URL or link.
     *
     * <p>This method uses regular expressions to check if the input string
     * matches common URL patterns starting with http, https, or www.</p>
     *
     * @param input The string to check
     * @return true if the string matches URL patterns, false otherwise
     */
    private static boolean isLink(String input) {
        // Define a regex pattern for URLs
        String regex = "^(http|https|www)\\S+";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(input);
        return matcher.find();
    }

    /**
     * Processes a URL by downloading its content, extracting words and links.
     *
     * <p>This method performs the following operations:</p>
     * <ol>
     *   <li>Downloads the web page content</li>
     *   <li>Removes non-content elements (scripts, styles, etc.)</li>
     *   <li>Extracts and processes text content for indexing</li>
     *   <li>Normalizes and transmits words for storage</li>
     *   <li>Extracts links for further processing</li>
     * </ol>
     *
     * @param url The URL to process
     */
    private static void processUrl(String url) {
        try {
            // Download the web page content using JSoup
            Document doc = Jsoup.connect(url).get();

            // Remove non-content elements that wouldn't contribute to meaningful indexing
            doc.select("script, style, nav, footer, header, aside").remove();

            // Extract the text content from the document body
            String text = doc.body().text();

            // Process each word in the text
            for (String word : text.split("\\s+")) {
                // Skip empty words and URLs
                if (word.isEmpty() || isLink(word)) continue;

                // Split compound words joined by punctuation
                String[] splitWords = splitByPunctuation(word);

                for (String part : splitWords) {
                    // Clean, normalize, and convert to lowercase
                    part = cleanWord(part);
                    part = normalizeText(part);
                    part = part.toLowerCase();

                    // Skip empty words or words without letters
                    if (part.isEmpty() || !containsLetter(part)) continue;

                    // Transmit the word and URL via multicast for indexing
                    String message = part + ";" + url;
                    multicast.sendMessage(message);
                }
            }

            // Extract and process links from the document
            processLinks(doc, url);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Extracts and processes links from a web document.
     *
     * <p>This method identifies all hyperlinks in the document, converts them to
     * absolute URLs, adds them to the processing queue, and transmits link
     * relationship information via multicast for building the web graph.</p>
     *
     * @param document The JSoup Document containing the HTML content
     * @param sourceUrl The URL of the source document
     */
    private static void processLinks(Document document, String sourceUrl) {
        try {
            // Select all hyperlink elements from the document
            Elements links = document.select("a[href]");

            for (Element link : links) {
                // Convert relative URLs to absolute URLs
                String absUrl = link.attr("abs:href");

                if (!absUrl.isEmpty()) {
                    // Add the found URL to the processing queue
                    urlQueueInterface.addUrl(absUrl);

                    // Transmit link relationship information via multicast
                    String message = "addLink" + ";" + sourceUrl + ";" + absUrl;
                    multicast.sendMessage(message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * The main entry point that initiates the concurrent URL processing.
     *
     * <p>This method creates a thread pool of downloaders to process URLs
     * concurrently, improving throughput and efficiency.</p>
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) {
        // Define the number of concurrent downloader threads to use
        int numThreads = 5;

        // Create a fixed-size thread pool for concurrent processing
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);

        // Submit multiple downloader instances to the thread pool
        for (int i = 0; i < numThreads; i++) {
            executorService.submit(new Downloader());
        }

        // Initiate an orderly shutdown of the thread pool
        executorService.shutdown();
    }
}