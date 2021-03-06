package Message;

import Peer.Peer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static Utilities.Constants.*;

public class Mailman {

    private Message message;
    private Peer peer;
    private String addr;
    private int port;
    private Runnable mailman;
    private String messageType;
    private String type;

    /**
     * Object in charge of receiving datagram packet and creating the corresponding message
     * and send it to the right handler.
     *
     * @param message received datagram packet
     * @param creator peer that received the packet
     */
    public Mailman(DatagramPacket message, Peer creator) {
        this.message = new Message(message);
        this.mailman = new ReceiverThread();
        this.type = "RECEIVER";
        this.peer = creator;
    }

    /**
     * Receives a message the is going to be sent and handles it accordingly to the message type
     *
     * @param message message to be sent
     * @param creator peer that is sending the message
     */
    public Mailman(Message message, Peer creator) {
        this.message = message;
        this.mailman = new SenderThread();
        this.type = "SENDER";
        this.peer = creator;
    }

    /**
     * Mailman that actually send the message to the multicast
     *
     * @param message message to be sent to the multicast
     * @param addr multicast address
     * @param port multicast port
     * @param messageType message type
     * @param peer peer that is sending the message
     */
    public Mailman(Message message, String addr, int port, String messageType, Peer peer) {
        this.message = message;
        this.addr = addr;
        this.port = port;
        this.type = "DELIVER";
        this.messageType = messageType;
        this.mailman = new DeliverMessageThread();
        this.peer = peer;
    }

    /**
     * Starts mailman thread based on type
     * This way there is a pool thread for sending and receiving messages
     * allowing them to not enter in deadlock if message incoming rate
     * is bigger than outgoing
     */
    public void startMailmanThread() {
        switch (type) {
            case "SENDER":
                peer.getSenderExecutor().submit(mailman);
                break;
            case "RECEIVER":
                peer.getReceiverExecutor().submit(mailman);
                break;
            case "DELIVER":
                peer.getDeliverExecutor().submit(mailman);
                break;
            default:
                break;
        }
    }

    /**
     * Delivers a message with the following format:
     * <MessageType> <Version> <SenderId> <FileId> <ChunkNo> [<ReplicationDeg>] <CRLF><CRLF>[<Body>]
     * to the specified multicast channel
     *
     * @param message     message containing the necessary info
     * @param addr        address of the multicast channel
     * @param port        port of the multicast channel
     * @param messageType message type, in case of PUTCHUNK, the type is necessary for retrieving the body of the message
     */
    private void deliverMessage(Message message, String addr, int port, String messageType) {

        DatagramSocket socket;
        DatagramPacket packet;

        try {
            socket = new DatagramSocket();
            byte[] buf = message.getMessageBytes(messageType);
            InetAddress address = InetAddress.getByName(addr.replace("/", ""));
            packet = new DatagramPacket(buf, buf.length, address, port);
            socket.send(packet);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Thread that delivers any message to a given multicast
     */
    public class DeliverMessageThread implements Runnable {
        public void run() {
            deliverMessage(message, addr, port, messageType);
        }
    }

    /**
     * Thread in charge of sending messages base on type
     */
    public class SenderThread implements Runnable {
        public void run() {
            System.out.println("Sended request:" + message.getMessageHeader().getMessageType());
            switch (message.getMessageHeader().getMessageType()) {
                case PUTCHUNK:
                    peer.getBackup().deliverPutchunkMessage(message);
                    break;
                case STORED:
                    if (peer.getVersion().equals("1.0")) {
                        peer.getBackup().deliverStoredMessage(message);
                    } else {
                        peer.getBackup().deliverStoredMessageEnhanced(message);
                        System.out.print("Enhanced BACKUP");
                    }
                    break;
                case GETCHUNK:
                    peer.getRestoreProtocol().deliverGetchunkMessage(message);
                    break;
                case DELETE:
                    if (peer.getVersion().equals("1.0"))
                        peer.getDeleteProtocol().deliverDeleteMessage(message);
                    else
                        peer.getDeleteProtocol().deliverDeleteMessage(message);
                    break;
                case REMOVED:
                    peer.getSpaceReclaimProtocol().deliverRemovedMessage(message);
                    break;
                case ALIVE:
                    peer.getDeleteProtocol().deliverAliveMessage(message);
                    break;
                default:
                    break;
            }
        }

    }

    /**
     * Handles received requestes
     */
    public class ReceiverThread implements Runnable {
        public void run() {
            //Ignores requests sent by itself
            if (message.getMessageHeader().getSenderId().equals(peer.getPeerId()))
                return;
            switch (message.getMessageHeader().getMessageType()) {
                case PUTCHUNK:
                    peer.getSpaceReclaimProtocol().increaseReceivedPUTCHUNK(message);
                    if (peer.getVersion().equals("1.0")) {
                        peer.getBackup().storeChunk(message);
                    } else {
                        peer.getBackup().storeChunkEnhanced(message);
                    }
                    break;
                case STORED:
                    if (peer.getVersion().equals("1.0")) {
                        peer.increaseReplicationDegree(message);
                    } else {
                        peer.increaseReplicationDegree(message);
                        peer.removeMessageFromStackDelete(message.getMessageHeader().getFileId());
                    }
                    break;
                case GETCHUNK:
                    peer.getRestoreProtocol().sendChunk(message);
                    break;
                case CHUNK:
                    peer.getRestoreProtocol().saveChunk(message);
                    break;
                case REMOVED:
                    peer.getSpaceReclaimProtocol().updateChunkRepDegree(message);
                    break;
                case DELETE:
                    if (peer.getVersion().equals("1.0"))
                        peer.getDeleteProtocol().deleteChunks(message.getMessageHeader().getFileId());
                    else {
                        peer.getDeleteProtocol().deleteChunks(message.getMessageHeader().getFileId());
                        peer.addMessageToStackDelete(message);
                    }
                    break;
                case ALIVE:
                    peer.getDeleteProtocol().resendDeleteMessage();
                    break;
                default:
                    break;
            }
        }
    }


}
