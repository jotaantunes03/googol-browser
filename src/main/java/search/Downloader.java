package search;

import java.io.FileInputStream;
import java.io.InputStream;
import java.rmi.registry.*;
import java.rmi.RemoteException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;

import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import search.Sockets.ReliableMulticast;

/**
 * The Downloader class is responsible for retrieving web content, processing it,
 * and extracting relevant data for indexing. It uses parallel streams to process
 * multiple URLs concurrently.
 *
 * <p>This class performs the following key functions:</p>
 * <ul>
 *   <li>Fetches URLs from a distributed queue</li>
 *   <li>Downloads and parses web page content in parallel</li>
 *   <li>Extracts and normalizes words for indexing</li>
 *   <li>Transmits index data via reliable multicast</li>
 *   <li>Processes and extracts links from web pages</li>
 *   <li>Implements reconnection mechanisms for handling URLQueue and Multicast failures</li>
 * </ul>
 *
 * <p>The class uses RMI (Remote Method Invocation) to communicate with a URL queue
 * and multicast sockets to distribute the processed data to storage barrels.</p>
 * 
 * @author João Antunes, David Cameijo and Gabriel Pinto
 */
public class Downloader {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Interface for accessing the index storage barrel through RMI */
    private static IndexStorageBarrelInterface indexStorageBarrelInterface;

    /** Interface for accessing the URL queue through RMI */
    private static URLQueueInterface urlQueueInterface;

    /** The multicast group address for distributed communication */
    private static String GROUP_ADDRESS = "230.0.0.0";

    /** The port number for multicast communication */
    private static int PORT = 4446;

    /** Reliable multicast instance for sending processed data */
    private static ReliableMulticast multicast;

    /** Maximum number of connection retry attempts */
    private static final int MAX_RETRY_ATTEMPTS = 5;

    /** Delay between connection retry attempts in milliseconds */
    private static final int RETRY_DELAY_MS = 5000;

    /** RMI registry port for URL queue service */
    private static int URL_QUEUE_PORT = 8184;

    /** Flag to track if system is operational */
    private static boolean isOperational = false;

    private static String QUEUE_IP = "localhost";


    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new Downloader instance, initializing the RMI connections
     * and multicast communication infrastructure.
     *
     * <p>The constructor establishes a connection to the URL queue service via RMI
     * and initializes the reliable multicast communication channel.</p>
     * <p>It includes robust reconnection mechanisms for both the URL queue and multicast services.</p>
     */
    public Downloader() {
        try (InputStream input = new FileInputStream("../config.properties")) {
            Properties prop = new Properties();
            prop.load(input);
            GROUP_ADDRESS = prop.getProperty("MULTICAST_ADDRESS");
            URL_QUEUE_PORT = Integer.parseInt(prop.getProperty("URL_QUEUE_PORT"));
            PORT = Integer.parseInt(prop.getProperty("PORT_MULTICAST_COMMUNICATION"));
            QUEUE_IP = prop.getProperty("QUEUE_IP");
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }














        // Initialize connections with retry mechanisms
        initializeMulticast();
        initializeURLQueue();

        // Set operational status based on successful connections
        isOperational = (multicast != null && urlQueueInterface != null);

        if (isOperational) {
            System.out.println("Downloader initialized successfully and is operational.");
        } else {
            System.err.println("Downloader initialization failed. Some services are unavailable.");
        }
    }

    /**
     * Initializes the multicast communication infrastructure with retry mechanism.
     *
     * <p>This method attempts to establish a connection to the multicast group
     * multiple times in case of initial failure.</p>
     *
     * @return true if multicast was successfully initialized, false otherwise
     */
    private boolean initializeMulticast() {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);
                System.out.println("Successfully connected to multicast group on attempt " + attempt);
                return true;
            } catch (IOException e) {
                System.err.println("Attempt " + attempt + " to connect to multicast group failed: " + e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    System.out.println("Retrying multicast connection in " + (RETRY_DELAY_MS / 1000) + " seconds...");
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("Multicast reconnection attempt interrupted: " + ie.getMessage());
                    }
                } else {
                    System.err.println("Failed to connect to multicast group after " + MAX_RETRY_ATTEMPTS + " attempts.");
                }
            }
        }
        return false;
    }

    /**
     * Initializes the connection to the URL Queue service via RMI with retry mechanism.
     *
     * <p>This method attempts to establish a connection to the URL Queue service
     * multiple times in case of initial failure.</p>
     *
     * @return true if URL Queue connection was successfully initialized, false otherwise
     */
    private boolean initializeURLQueue() {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Registry registryQueue = LocateRegistry.getRegistry(QUEUE_IP, URL_QUEUE_PORT);
                urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");
                System.out.println("Successfully connected to URL Queue service on attempt " + attempt);
                return true;
            } catch (Exception e) {
                System.err.println("Attempt " + attempt + " to connect to URL Queue service failed: " + e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    System.out.println("Retrying URL Queue connection in " + (RETRY_DELAY_MS / 1000) + " seconds...");
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("URL Queue reconnection attempt interrupted: " + ie.getMessage());
                    }
                } else {
                    System.err.println("Failed to connect to URL Queue service after " + MAX_RETRY_ATTEMPTS + " attempts.");
                }
            }
        }
        return false;
    }

    /**
     * Attempts to reestablish a connection to the URL Queue service when a failure is detected.
     *
     * <p>This method is called when an operation using the URL Queue fails,
     * and it attempts to reconnect to the service.</p>
     *
     * @return true if reconnection was successful, false otherwise
     */
    private static boolean reconnectURLQueue() {
        System.out.println("Attempting to reconnect to URL Queue service...");

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Registry registryQueue = LocateRegistry.getRegistry(QUEUE_IP, URL_QUEUE_PORT);
                urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");
                System.out.println("Successfully reconnected to URL Queue service on attempt " + attempt);
                return true;
            } catch (Exception e) {
                System.err.println("Reconnection attempt " + attempt + " to URL Queue service failed: " + e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("URL Queue reconnection attempt interrupted: " + ie.getMessage());
                    }
                }
            }
        }

        System.err.println("Failed to reconnect to URL Queue service after " + MAX_RETRY_ATTEMPTS + " attempts.");
        return false;
    }

    /**
     * Attempts to reestablish a connection to the multicast group when a failure is detected.
     *
     * <p>This method is called when an operation using the multicast fails,
     * and it attempts to reconnect to the multicast group.</p>
     *
     * @return true if reconnection was successful, false otherwise
     */
    private static boolean reconnectMulticast() {
        System.out.println("Attempting to reconnect to multicast group...");

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);
                System.out.println("Successfully reconnected to multicast group on attempt " + attempt);
                return true;
            } catch (IOException e) {
                System.err.println("Reconnection attempt " + attempt + " to multicast group failed: " + e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("Multicast reconnection attempt interrupted: " + ie.getMessage());
                    }
                }
            }
        }

        System.err.println("Failed to reconnect to multicast group after " + MAX_RETRY_ATTEMPTS + " attempts.");
        return false;
    }

    //----------------------------------------METHODS----------------------------------------

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
     * <p>This method includes error handling and reconnection mechanisms for both
     * JSoup connection failures and multicast transmission failures.</p>
     *
     * @param url The URL to process
     * @return true if processing was successful, false otherwise
     */
    private static boolean processUrl(String url) {
        // Skip processing if the URL is null or empty
        if (url == null || url.isEmpty()) {
            System.err.println("Attempted to process null or empty URL");
            return false;
        }

        System.out.println("Processing URL: " + url);

        try {
            // Download the web page content using JSoup
            Document doc = Jsoup.connect(url)
                    .timeout(1000000)  // Set a 1000-second timeout
                    .userAgent("Mozilla/5.0")  // Use a common user agent
                    .ignoreHttpErrors(true)  // Continue even if HTTP errors occur
                    .get();

            // Check if the document was successfully retrieved
            if (doc == null || doc.body() == null) {
                System.err.println("Failed to retrieve valid content from URL: " + url);
                return false;
            }

            // Remove non-content elements that wouldn't contribute to meaningful indexing
            doc.select("script, style, nav, footer, header, aside").remove();

            // Extract the text content from the document body
            String text = doc.body().text();

            if (text == null || text.isEmpty()) {
                System.out.println("No text content found at URL: " + url);
                return true;  // Return true as this is a valid state, just no content
            }

            // Process each word in the text - using streams for parallel processing
            boolean allWordsProcessed = Arrays.stream(text.split("\\s+"))
                    .parallel()
                    .filter(word -> !word.isEmpty() && !isLink(word))
                    .flatMap(word -> Arrays.stream(splitByPunctuation(word)))
                    .map(Downloader::cleanWord)
                    .map(Downloader::normalizeText)
                    .map(String::toLowerCase)
                    .filter(part -> !part.isEmpty() && containsLetter(part))
                    .map(part -> {
                        try {
                            // Transmit the word and URL via multicast for indexing
                            String message = part + ";" + url;
                            multicast.sendMessage(message);
                            return true;
                        } catch (IOException e) {
                            System.err.println("Multicast transmission failed for word '" + part + "': " + e.getMessage());
                            return false;
                        }
                    })
                    .allMatch(Boolean::booleanValue);  // Check if all words were processed successfully

            // If multicast transmission failed, attempt reconnection
            if (!allWordsProcessed) {
                System.err.println("Some words failed to transmit for URL: " + url);
                boolean reconnected = reconnectMulticast();
                if (!reconnected) {
                    System.err.println("Unable to reconnect to multicast. Skipping link processing for URL: " + url);
                    return false;
                }
            }

            // Extract and process links from the document
            return processLinks(doc, url);

        } catch (IOException e) {
            System.err.println("Failed to process URL '" + url + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Extracts and processes links from a web document.
     *
     * <p>This method identifies all hyperlinks in the document, converts them to
     * absolute URLs, adds them to the processing queue, and transmits link
     * relationship information via multicast for building the web graph.</p>
     *
     * <p>The method includes error handling and reconnection mechanisms for both
     * URL Queue and multicast failures.</p>
     *
     * @param document The JSoup Document containing the HTML content
     * @param sourceUrl The URL of the source document
     * @return true if link processing was successful, false otherwise
     */
    private static boolean processLinks(Document document, String sourceUrl) {
        if (document == null || sourceUrl == null || sourceUrl.isEmpty()) {
            System.err.println("Invalid document or source URL provided to processLinks");
            return false;
        }

        try {
            // Select all hyperlink elements from the document
            Elements links = document.select("a[href]");

            if (links.isEmpty()) {
                System.out.println("No links found in document: " + sourceUrl);
                return true;  // Return true as this is a valid state, just no links
            }

            // Flag to track if all links were processed successfully
            final boolean[] allLinksProcessed = {true};

            // Process links in parallel
            links.parallelStream()
                    .map(link -> link.attr("abs:href"))
                    .filter(absUrl -> !absUrl.isEmpty())
                    .forEach(absUrl -> {
                        boolean linkProcessed = true;

                        // First, try to add the URL to the queue
                        try {
                            urlQueueInterface.addUrl(absUrl);
                        } catch (RemoteException e) {
                            System.err.println("Failed to add URL to queue: " + absUrl);

                            // Attempt to reconnect to the URL Queue
                            boolean reconnected = reconnectURLQueue();
                            if (reconnected) {
                                // Retry adding the URL after reconnection
                                try {
                                    urlQueueInterface.addUrl(absUrl);
                                } catch (RemoteException re) {
                                    System.err.println("Failed to add URL to queue even after reconnection: " + absUrl);
                                    linkProcessed = false;
                                }
                            } else {
                                linkProcessed = false;
                            }
                        }

                        // If adding to the queue was successful, transmit link relationship via multicast
                        if (linkProcessed) {
                            try {
                                String message = "addLink" + ";" + sourceUrl + ";" + absUrl;
                                multicast.sendMessage(message);
                            } catch (IOException e) {
                                System.err.println("Failed to send link relationship via multicast: " + sourceUrl + " -> " + absUrl);

                                // Attempt to reconnect to the multicast group
                                boolean reconnected = reconnectMulticast();
                                if (reconnected) {
                                    // Retry sending the message after reconnection
                                    try {
                                        String message = "addLink" + ";" + sourceUrl + ";" + absUrl;
                                        multicast.sendMessage(message);
                                    } catch (IOException re) {
                                        System.err.println("Failed to send link relationship via multicast even after reconnection");
                                        linkProcessed = false;
                                    }
                                } else {
                                    linkProcessed = false;
                                }
                            }
                        }

                        // Update the overall success status
                        if (!linkProcessed) {
                            allLinksProcessed[0] = false;
                        }
                    });

            return allLinksProcessed[0];

        } catch (Exception e) {
            System.err.println("Error processing links from document: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Processes a batch of URLs retrieved from the queue in parallel.
     *
     * <p>This method retrieves a batch of URLs from the queue and processes them
     * in parallel. It includes error handling and reconnection mechanisms for
     * URL Queue failures.</p>
     *
     * @param batchSize The number of URLs to process in one batch
     * @return The number of URLs successfully processed
     */
    private static int processBatch(int batchSize) {
        List<String> urlBatch = new ArrayList<>();
        int processedCount = 0;

        try {
            // Retrieve URLs from the queue up to the batch size
            for (int i = 0; i < batchSize; i++) {
                try {
                    String url = urlQueueInterface.takeUrl();
                    if (url == null) break;
                    urlBatch.add(url);
                } catch (RemoteException e) {
                    System.err.println("Failed to retrieve URL from queue: " + e.getMessage());

                    // Attempt to reconnect to the URL Queue
                    boolean reconnected = reconnectURLQueue();
                    if (reconnected) {
                        // Continue retrieving URLs after reconnection
                        try {
                            String url = urlQueueInterface.takeUrl();
                            if (url == null) break;
                            urlBatch.add(url);
                        } catch (RemoteException re) {
                            System.err.println("Failed to retrieve URL from queue even after reconnection: " + re.getMessage());
                            break;
                        }
                    } else {
                        // If reconnection failed, break out of the loop
                        break;
                    }
                }
            }

            // Process the URLs in parallel if any were retrieved
            if (!urlBatch.isEmpty()) {
                System.out.println("Processing batch of " + urlBatch.size() + " URLs...");

                // Count successfully processed URLs
                processedCount = (int) urlBatch.parallelStream()
                        .map(Downloader::processUrl)
                        .filter(Boolean::booleanValue)
                        .count();

                System.out.println("Successfully processed " + processedCount + " out of " + urlBatch.size() + " URLs");
            } else {
                System.out.println("No URLs retrieved from queue");
            }

            return processedCount;

        } catch (Exception e) {
            System.err.println("Error processing URL batch: " + e.getMessage());
            e.printStackTrace();
            return processedCount;
        }
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * The main entry point that initiates parallel URL processing.
     *
     * <p>This method retrieves batches of URLs and processes them in parallel
     * using Java streams, improving throughput and efficiency. It includes
     * robust error handling and reconnection mechanisms.</p>
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            // Initialize the downloader
            Downloader downloader = new Downloader();

            // If initialization failed, exit the program
            if (!isOperational) {
                System.err.println("Downloader failed to initialize critical components. Exiting...");
                System.exit(1);
            }

            // Define the batch size for parallel processing
            int batchSize = 10;

            // Continuously process URLs
            while (true) {
                int processedCount = processBatch(batchSize);

                // If no URLs were processed, wait 1 second before trying again
                if (processedCount == 0) {
                    System.out.println("No URLs in queue. Sleeping for 1 second.");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Sleep interrupted: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal error in Downloader: " + e.getMessage());
            e.printStackTrace();
        }
    }

}