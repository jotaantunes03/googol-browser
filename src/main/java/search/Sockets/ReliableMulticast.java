package search.Sockets;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

/**
 * Class for managing reliable multicast communication. It allows sending and receiving
 * messages within a multicast group and ensures that messages are delivered reliably.
 */
public class ReliableMulticast {

    /** Multicast socket for communication */
    private MulticastSocket socket;

    /** Multicast group address */
    private InetAddress group;

    /** Port for multicast communication */
    private int port;

    /** Buffer size for receiving multicast messages */
    private static final int BUF_SIZE = 1024;

    /**
     * Constructor for setting up the multicast socket, joining the multicast group, and initializing communication.
     *
     * @param groupAddress The address of the multicast group.
     * @param port The port used for communication.
     * @throws IOException If there is an issue setting up the socket or joining the group.
     */
    public ReliableMulticast(String groupAddress, int port) throws IOException {
        this.port = port;
        this.group = InetAddress.getByName(groupAddress);
        this.socket = new MulticastSocket(port);
        socket.setTimeToLive(1);

        // Get the network interface for the local host
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());

        // Join the multicast group using the new method
        InetSocketAddress groupSocketAddress = new InetSocketAddress(group, port);
        socket.joinGroup(groupSocketAddress, networkInterface);

        System.out.println("Joined multicast group: " + groupAddress + " on port " + port);
    }

    /**
     * Method to send a message to the multicast group.
     *
     * @param message The message to be sent.
     * @throws IOException If there is an issue sending the message.
     */
    public void sendMessage(String message) throws IOException {
        byte[] buf = message.getBytes();
        DatagramPacket packet = new DatagramPacket(buf, buf.length, group, port);
        socket.send(packet);
        System.out.println("Sent message: " + message);
    }

    /**
     * Method to receive messages from the multicast group.
     *
     * @return The received message as a String.
     * @throws IOException If there is an issue receiving the message.
     */
    public String receiveMessage() throws IOException {
        byte[] buf = new byte[BUF_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        String received = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Received message: " + received);
        return received;
    }

    /**
     * Method to leave the multicast group and close the socket.
     *
     * @throws IOException If there is an issue leaving the group or closing the socket.
     */
    public void leaveGroup() throws IOException {
        // Get the network interface again before leaving the group
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());

        // Leave the group using the same interface and address
        InetSocketAddress groupSocketAddress = new InetSocketAddress(group, port);
        socket.leaveGroup(groupSocketAddress, networkInterface);

        socket.close();
        System.out.println("Left multicast group and closed socket.");
    }
}
