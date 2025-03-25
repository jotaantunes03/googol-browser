package search;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.net.URI;
import java.util.stream.Stream;

/**
 * The GoogolClient class provides a command-line interface for users to interact
 * with the distributed search engine system. It enables searching for words,
 * submitting URLs for indexing, and checking inbound links to specific pages.
 *
 * <p>This client application communicates with the Gateway service through
 * RMI (Remote Method Invocation) to access the distributed search functionality.</p>
 *
 * <p>Key features include:</p>
 * <ul>
 *   <li>Simple text-based user interface</li>
 *   <li>URL submission for indexing</li>
 *   <li>Word search functionality</li>
 *   <li>Inbound link analysis for web pages</li>
 *   <li>URL validation to ensure proper format</li>
 * </ul>
 *
 * @author João Antunes, David Cameijo and Gabriel Pinto
 */
public class GoogolClient {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Scanner for reading user input from the console */
    private Scanner scanner;

    /** Interface for communicating with the Gateway service */
    private static GatewayInterface gateway;

    private static int GATEWAY_PORT = 8185;

    private static String GATEWAY_IP = "localhost";



    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new GoogolClient instance.
     *
     * <p>Initializes the scanner for reading user input from the command line.</p>
     */
    public GoogolClient() {
        try (InputStream input = new FileInputStream("../config.properties")) {
            Properties prop = new Properties();
            prop.load(input);

            GATEWAY_PORT = Integer.parseInt(prop.getProperty("GATEWAY_PORT"));
            GATEWAY_IP = prop.getProperty("GatewayIP");

        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }


        scanner = new Scanner(System.in);
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * The main entry point for the GoogolClient application.
     *
     * <p>This method establishes a connection to the Gateway service via RMI
     * and launches the interactive menu for the user.</p>
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            GoogolClient client = new GoogolClient();

            // Connect to the Gateway service via RMI
            Registry registry = LocateRegistry.getRegistry(GATEWAY_IP, GATEWAY_PORT);
            gateway = (GatewayInterface) registry.lookup("GatewayService");

            // Launch the interactive menu
            client.menu();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------MENU----------------------------------------

    /**
     * Displays the main menu and processes user input.
     *
     * <p>This method creates an interactive loop that displays menu options,
     * reads the user's selection, and executes the corresponding functionality.</p>
     */
    private void menu() {
        try {
            boolean stopServer = false;

            while (!stopServer) {
                // Display menu options
                System.out.println("\n<<<<<<<< Googol - Motor de Pesquisa >>>>>>>>");
                System.out.println("[1] Indexar novo URL");
                System.out.println("[2] Realizar uma pesquisa");
                System.out.println("[3] Consultar ligações para uma página específica");
                System.out.println("[4] Listar estatísticas do sistema");
                System.out.println("[5] Sair");
                System.out.print("Escolha uma opção: ");

                // Read user selection
                String userOption = scanner.nextLine();
                clearConsole();

                // Process the selected option
                switch (userOption) {
                    case "1" -> addUrl();
                    case "2" -> searchWord();
                    case "3" -> checkInboundLinks();
                    case "4" -> listStats();
                    case "5" -> {
                        stopServer = true;
                        System.out.println("A sair do Googol...");
                    }
                    default -> System.out.println("Opção inválida! Escolha novamente.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------CLIENT FUNCTIONS----------------------------------------

    /**
     * Allows the user to submit a URL for indexing.
     *
     * <p>This method prompts the user for a URL, validates its format,
     * and submits it to the Gateway for indexing if valid.</p>
     */
    private void addUrl() {
        try {
            while (true) {
                System.out.println("Insira [exit] para voltar atrás.");
                System.out.print("Inserir URL: ");
                String userUrl = scanner.nextLine();

                if (userUrl.equalsIgnoreCase("exit")) {
                    clearConsole();
                    break;
                } else if (!isValidUrl(userUrl)) {
                    clearConsole();
                    System.out.println("ERRO: URL inválido! Tente novamente.");
                } else {
                    clearConsole();
                    gateway.addUrl(userUrl);
                    System.out.println("✅ URL enviado para indexação!");
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Allows the user to search for a word in the index.
     *
     * <p>This method prompts the user for a search term, queries the Gateway
     * for matching URLs, and displays the results.</p>
     */
    private void searchWord() {
        try {
            System.out.print("Digite a palavra a pesquisar: ");
            String searchWord = scanner.nextLine();
            List<String> results = gateway.search(searchWord);

            if (results.isEmpty()) {
                System.out.println("Nenhum resultado encontrado para a palavra '" + searchWord + "'.");
            } else {
                System.out.println("\nResultados encontrados:");
                printResultsInPages(results);
            }
        } catch (Exception e) {
            System.out.println("Erro ao pesquisar palavra.");
            e.printStackTrace();
        }
    }

    /**
     * Allows the user to check which pages link to a specific URL.
     *
     * <p>This method prompts the user for a URL and displays all pages
     * that contain links to that URL based on the indexed data.</p>
     */
    private void checkInboundLinks() {
        try {
            System.out.print("Digite o URL para ver as ligações recebidas: ");
            String url = scanner.nextLine();
            List<String> inboundLinks = gateway.checkInboundLinks(url);

            if (inboundLinks.isEmpty()) {
                System.out.println("Nenhuma página aponta para '" + url + "'.");
            } else {
                System.out.println("\nPáginas que apontam para " + url + ":");
                inboundLinks.forEach(link -> System.out.println("🔗 " + link));
            }
        } catch (Exception e) {
            System.out.println("Erro ao obter ligações recebidas.");
            e.printStackTrace();
        }
    }
    
    /**
     * Prints search results grouped in pages of 10 items each.
     *
     * @param results The complete list of search results.
     */
    public static void printResultsInPages(List<String> results) {
        final int pageSize = 10;
        int total = results.size();
        Scanner scanner = new Scanner(System.in);

        for (int i = 0; i < total; i += pageSize) {
            System.out.println("Page " + ((i / pageSize) + 1) + ":");

            // Define the end of the current group
            int end = Math.min(i + pageSize, total);
            for (int j = i; j < end; j++) {
                System.out.println(results.get(j));
            }

            System.out.println(); // Blank line to separate pages

            // If there are more pages, wait for the user to press ENTER to continue
            if (end < total) {
                System.out.println("Press ENTER to view the next page or 'M' to return to the menu...");
                String choice = scanner.nextLine().toUpperCase();

                if ("M".equals(choice)) {
                    System.out.println("Returning to the menu...");
                    return; // Exit this method and go back to the menu
                }
            }
        }
    }

    /**
     * New method to list system statistics.
     */
    private void listStats() {
        try {
            // Assuming getSystemState() is defined in the GatewayInterface
            String systemState = gateway.getSystemState();
            System.out.println("\nEstatísticas do Sistema:");
            System.out.println(systemState);
        } catch (Exception e) {
            System.out.println("Erro ao obter estatísticas do sistema.");
            e.printStackTrace();
        }
    }
    //----------------------------------------AUXILIARY METHODS----------------------------------------

    /**
     * Clears the console screen for improved user interface readability.
     *
     * <p>This method detects the operating system and executes the appropriate
     * command to clear the console screen.</p>
     */
    public final static void clearConsole() {
        try {
            final String os = System.getProperty("os.name");
            if (os.contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Validates if a string represents a properly formatted URL.
     *
     * <p>This method attempts to parse the input string as a URL to verify
     * that it conforms to the expected format.</p>
     *
     * @param url The string to validate as a URL
     * @return true if the string is a valid URL, false otherwise
     */
    public static boolean isValidUrl(String url) {
        try {
            URI uri = new URI(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}