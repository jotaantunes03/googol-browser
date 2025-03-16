package search;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Scanner;
import java.net.URL;

/**
 * Cliente que interage com a Gateway para pesquisar palavras e indexar URLs.
 * A comunicação é feita via RMI, utilizando a Gateway como intermediária.
 */
public class GoogolClient {

    private Scanner scanner;
    private static GatewayInterface gateway;

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Construtor do GoogolClient.
     * Inicializa o scanner para interagir com o utilizador.
     */
    public GoogolClient() {
        scanner = new Scanner(System.in);
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * Método principal que inicia o cliente e se conecta à Gateway.
     *
     * @param args Argumentos da linha de comandos.
     */
    public static void main(String[] args) {
        try {
            GoogolClient client = new GoogolClient();

            // Conectar ao servidor da Gateway RMI
            Registry registry = LocateRegistry.getRegistry(8185);
            gateway = (GatewayInterface) registry.lookup("GatewayService");

            client.menu();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------MENU----------------------------------------

    /**
     * Exibe o menu e permite ao utilizador escolher uma ação.
     */
    private void menu() {
        try {
            boolean stopServer = false;

            while (!stopServer) {
                System.out.println("\n<<<<<<<< Googol - Motor de Pesquisa >>>>>>>>");
                System.out.println("[1] Indexar novo URL");
                System.out.println("[2] Realizar uma pesquisa");
                System.out.println("[3] Consultar ligações para uma página específica");
                System.out.println("[4] Sair");
                System.out.print("Escolha uma opção: ");

                String userOption = scanner.nextLine();
                clearConsole();

                switch (userOption) {
                    case "1" -> addUrl();
                    case "2" -> searchWord();
                    case "3" -> checkInboundLinks();
                    case "4" -> {
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

    //----------------------------------------FUNÇÕES DO CLIENTE----------------------------------------

    /**
     * Permite ao utilizador inserir um URL para ser indexado pela Gateway.
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
     * Permite ao utilizador pesquisar uma palavra no índice através da Gateway.
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
                results.forEach(url -> System.out.println("🔗 " + url));
            }
        } catch (Exception e) {
            System.out.println("Erro ao pesquisar palavra.");
            e.printStackTrace();
        }
    }

    /**
     * Permite ao utilizador consultar todas as páginas que apontam para um determinado URL.
     */
    private void checkInboundLinks() {
        try {
            System.out.print("Digite o URL para ver as ligações recebidas: ");
            String url = scanner.nextLine();
            List<String> inboundLinks = gateway.search(url);

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

    //----------------------------------------MÉTODOS AUXILIARES----------------------------------------

    /**
     * Método para limpar a consola.
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
     * Verifica se um URL é válido.
     *
     * @param url O URL a ser verificado.
     * @return true se for válido, false caso contrário.
     */
    public static boolean isValidUrl(String url) {
        try {
            new URL(url).toURI();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
