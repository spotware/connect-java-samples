package com.mycompany.app;

import com.google.protobuf.InvalidProtocolBufferException;
import com.xtrader.protocol.proto.commons.ProtoMessage;
import com.xtrader.protocol.proto.commons.model.ProtoTradeSide;
import com.xtrader.protocol.proto.openapi.ProtoOAExecutionEvent;
import com.xtrader.protocol.proto.openapi.model.ProtoOAPayloadType;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Hello world!
 */
public class TradingApiTest {
    private static final String API_HOST = "demo-hub-cons.p.ctrader.com";
    private static final int API_PORT = 5032;
    private static final String CLIENT_PUBLIC_ID = "1_ub7tb0bh2s0ck8wss84k4c0sww8sskgwc0o0go0o8k48oo4ko";
    private static final String CLIENT_SECRET = "2uto9lh3qigwg0sg8ococ0wgw8ck8s0wkcocgcoowc4k4gow0c";
    private static final long TRADING_ACCOUNT_ID = 62002; // login 3000041 pass:123456 on http://sandbox-ct.spotware.com
    private static final String TRADING_API_TOKEN = "test002_access_token";

    private static long testPositionId = -1;
    private static long testVolume = 1000000;
    private static String clientMsgId = null;
    private static int sendMsgTimeout = 20;
    private static long lastSentMsgTimestamp = System.currentTimeMillis() + (sendMsgTimeout * 1000);
    private static int MaxMessageSize = 1000000;
    private static boolean isDebugIsOn = true;

    volatile static boolean isShutdown;
    volatile static boolean isRestart;
    private static Queue writeQueueSync = new ConcurrentLinkedQueue();
    private static Queue readQueueSync = new ConcurrentLinkedQueue();

    private static InputStream apiInputStream;
    private static OutputStream apiOutputStream;

    private static OpenApiMessagesFactory incomingMsgFactory = new OpenApiMessagesFactory();
    private static OpenApiMessagesFactory outgoingMsgFactory = new OpenApiMessagesFactory();

    // timer thread
    static void timer(OpenApiMessagesFactory msgFactory, Queue messagesQueue) throws InterruptedException, InvalidProtocolBufferException {
        isShutdown = false;
        while (!isShutdown) {
            Thread.sleep(1000);
            if (System.currentTimeMillis() > lastSentMsgTimestamp) {
                sendPingRequest(msgFactory, messagesQueue);
            }
        }
    }

    // listener thread
    private static void listen(InputStream inputStream, Queue messagesQueue) throws InterruptedException, IOException {
        isShutdown = false;
        while (!isShutdown) {
            Thread.sleep(1);
            byte[] _length = new byte[4];
            inputStream.read(_length, 0, _length.length);

            if (isDebugIsOn) {
                System.out.printf("Length Data received: %1$s" + "\r\n", Utils.getHexadecimal(_length));
            }

            int length = Utils.fromArray(_length);
            if (length <= 0) {
                continue;
            }

            if (length > MaxMessageSize) {
                String exceptionMsg = "Message length " + (new Integer(length)).toString() + " is out of range (0 - " + (new Integer(MaxMessageSize)).toString() + ")";
                throw new IndexOutOfBoundsException(exceptionMsg);
            }

            byte[] _message = new byte[length];
            inputStream.read(_message, 0, _message.length);
            if (isDebugIsOn) {
                System.out.printf("Message Data received: %1$s" + "\r\n", Utils.getHexadecimal(_message));
            }
            messagesQueue.offer(_message);
        }
    }

    // sender thread
    private static void transmit(OutputStream outputStream, Queue messagesQueue, long lastSentMsgTimestamp) throws InterruptedException, IOException {
        isShutdown = false;
        while (!isShutdown) {
            Thread.sleep(1);

            if (messagesQueue.size() <= 0) {
                continue;
            }
            byte[] _message = (byte[]) messagesQueue.poll();
            byte[] _length = Utils.toArray(_message.length);
            outputStream.write(_length);
            if (isDebugIsOn) {
                System.out.printf("Data sent: %1$s" + "\r\n", Utils.getHexadecimal(_length));
            }
            outputStream.write(_message);
            if (isDebugIsOn) {
                System.out.printf("Data sent: %1$s" + "\r\n", Utils.getHexadecimal(_message));
            }
            lastSentMsgTimestamp = System.currentTimeMillis() + sendMsgTimeout;
        }
    }

    // incoming data processing thread
    private static void incomingDataProcessing(OpenApiMessagesFactory msgFactory, Queue<byte[]> messagesQueue) throws InterruptedException, InvalidProtocolBufferException {
        isShutdown = false;
        while (!isShutdown) {
            Thread.sleep(0);
            if (messagesQueue.size() <= 0) {
                continue;
            }
            byte[] _message = messagesQueue.poll();
            processIncomingDataStream(msgFactory, _message);
        }
    }

    private static void processIncomingDataStream(OpenApiMessagesFactory msgFactory, byte[] rawData) throws InvalidProtocolBufferException {
        ProtoMessage _msg = msgFactory.getMessage(rawData);
        if (isDebugIsOn) {
            System.out.printf("ProcessIncomingDataStream() Message received:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
        }
        if (!_msg.hasPayload()) {
            return;
        }

        if (_msg.getPayloadType() == ProtoOAPayloadType.OA_EXECUTION_EVENT.getNumber()) {
            ProtoOAExecutionEvent _payload_msg = msgFactory.GetExecutionEvent(rawData);
            if (_payload_msg.hasPosition()) {
                testPositionId = _payload_msg.getPosition().getPositionId();
            }
        }
    }

    private static void sendPingRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createPingRequest(System.currentTimeMillis(), null);
            if (isDebugIsOn) {
                System.out.printf("sendPingRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendHeartbeatEvent(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createHeartbeatEvent(null);
            if (isDebugIsOn) {
                System.out.printf("sendHeartbeatEvent() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendAuthorizationRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createAuthorizationRequest(CLIENT_PUBLIC_ID, CLIENT_SECRET, null);
            if (isDebugIsOn) {
                System.out.printf("sendAuthorizationRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendSubscribeForTradingEventsRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createSubscribeForTradingEventsRequest(TRADING_ACCOUNT_ID, CLIENT_SECRET);
            if (isDebugIsOn) {
                System.out.printf("sendSubscribeForTradingEventsRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendUnsubscribeForTradingEventsRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createUnsubscribeForTradingEventsRequest(TRADING_ACCOUNT_ID, CLIENT_SECRET);
            if (isDebugIsOn) {
                System.out.printf("sendUnsubscribeForTradingEventsRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendGetAllSubscriptionsForTradingEventsRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createAllSubscriptionsForTradingEventsRequest();
            if (isDebugIsOn) {
                System.out.printf("sendGetAllSubscriptionsForTradingEventsRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendMarketOrderRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createMarketOrderRequest(TRADING_ACCOUNT_ID, TRADING_API_TOKEN, "EURUSD", ProtoTradeSide.BUY, testVolume, clientMsgId);
            if (isDebugIsOn) System.out.printf("sendMarketOrderRequest() Message to be send:\n{0}", OpenApiMessagesPresentation.toString(_msg));
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }
    private static void sendLimitOrderRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createLimitOrderRequest(TRADING_ACCOUNT_ID, TRADING_API_TOKEN, "EURUSD", ProtoTradeSide.BUY, 1000000, 1.8, clientMsgId);
            if (isDebugIsOn) System.out.printf("sendLimitOrderRequest() Message to be send:\n{0}", OpenApiMessagesPresentation.toString(_msg));
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendStopOrderRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createStopOrderRequest(TRADING_ACCOUNT_ID, TRADING_API_TOKEN, "EURUSD", ProtoTradeSide.BUY, 1000000, 0.2, clientMsgId);
            if (isDebugIsOn) System.out.printf("sendStopOrderRequest() Message to be send:\n{0}", OpenApiMessagesPresentation.toString(_msg));
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendClosePositionRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createClosePositionRequest(TRADING_ACCOUNT_ID, TRADING_API_TOKEN, testPositionId, testVolume, clientMsgId);
            if (isDebugIsOn) System.out.printf("SendClosePositionRequest() Message to be send:\n{0}", OpenApiMessagesPresentation.toString(_msg));
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendSubscribeForSpotsRequest(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        try {
            ProtoMessage _msg = msgFactory.createSubscribeForSpotsRequest(TRADING_ACCOUNT_ID, TRADING_API_TOKEN, "EURUSD", clientMsgId);
            if (isDebugIsOn) {
                System.out.printf("SendSubscribeForSpotsRequest() Message to be send:\n%1$s" + "\r\n", OpenApiMessagesPresentation.toString(_msg));
            }
            writeQueue.offer(_msg.toByteArray());
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }

    private static void notImplementedCommand(OpenApiMessagesFactory msgFactory, Queue writeQueue) {
        System.out.println("Action is NOT IMPLEMENTED!");
    }


    private static List<MenuItem> menuItems = new ArrayList<MenuItem>(Arrays.asList(new MenuItem[]
    {
            new MenuItem('P', "send ping request", TradingApiTest::sendPingRequest),
            new MenuItem('H', "send heartbeat event", TradingApiTest::sendHeartbeatEvent),
            new MenuItem('A', "send authorization request", TradingApiTest::sendAuthorizationRequest),
            new MenuItem('S', "send subscription request", TradingApiTest::sendSubscribeForTradingEventsRequest),
            new MenuItem('U', "send unsubscribe request", TradingApiTest::sendUnsubscribeForTradingEventsRequest),
            new MenuItem('G', "send getting all subscriptions request",TradingApiTest::sendGetAllSubscriptionsForTradingEventsRequest),
            new MenuItem('1', "send market order", TradingApiTest::sendMarketOrderRequest),
            new MenuItem('2', "send limit order", TradingApiTest::sendLimitOrderRequest),
            new MenuItem('3', "send stop order", TradingApiTest::sendStopOrderRequest),
            new MenuItem('4', "send market range order", TradingApiTest::notImplementedCommand),
            new MenuItem('9', "close last modified position", TradingApiTest::sendClosePositionRequest),
            new MenuItem('C', "cancel last pending order", TradingApiTest::notImplementedCommand),
            new MenuItem('L', "set loss level", TradingApiTest::notImplementedCommand),
            new MenuItem('T', "set profit level", TradingApiTest::notImplementedCommand),
            new MenuItem('X', "set expiration time (in secs)", TradingApiTest::notImplementedCommand),
            new MenuItem('0', "subscribe for EURUSD quites", TradingApiTest::sendSubscribeForSpotsRequest)
    }));


    public static void main(String[] args) throws InterruptedException, IOException {
        do {
            System.out.printf("Establishing trading SSL connection to %1$s:%2$s..." + "\r\n", API_HOST, API_HOST);
            try {
                Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket socket = (SSLSocket) factory.createSocket(API_HOST, API_PORT);
                apiInputStream = socket.getInputStream();
                apiOutputStream = socket.getOutputStream();
            } catch (Exception e) {
                System.out.printf("Establishing SSL connection error: %1$s" + "\r\n", e);
                return;
            }

            Thread p = new Thread(() -> {
                try {
                    incomingDataProcessing(incomingMsgFactory, readQueueSync);
                } catch (Exception e) {
                    System.out.printf("DataProcessor throws exception: %1$s" + "\r\n", e);
                }
            });
            p.setDaemon(true);
            p.start();

            Thread tl = new Thread(() ->
            {
                try {
                    listen(apiInputStream, readQueueSync);
                } catch (Exception e) {
                    System.out.printf("Listener throws exception: %1$s" + "\r\n", e);
                }
            });
            tl.setDaemon(true);
            tl.start();

            Thread ts = new Thread(() ->
            {
                try {
                    transmit(apiOutputStream, writeQueueSync, lastSentMsgTimestamp);
                } catch (Exception e) {
                    System.out.printf("Transmitter throws exception: %1$s" + "\r\n", e);
                }
            });
            ts.setDaemon(true);
            ts.start();

            Thread t = new Thread(() ->
            {
                try {
                    timer(outgoingMsgFactory, writeQueueSync);
                } catch (Exception e) {
                    System.out.printf("Listener throws exception: %1$s" + "\r\n", e);
                }
            });
            t.setDaemon(true);
            t.start();

            while (tl.isAlive() || t.isAlive() || p.isAlive() || ts.isAlive()) {
                System.out.println("List of actions");
                for (MenuItem m : menuItems) {
                    System.out.printf("%1$s: %2$s" + "\r\n", m.cmdKey, m.itemTitle);
                }
                System.out.println("----------------------------");
                System.out.println("R: reconnect");
                System.out.println("Q: quit");

                Thread.sleep(300);
                System.out.println("Enter the action to perform:");
                char cmd = (char) System.in.read();
                System.out.println();
                if (cmd == 'Q' || cmd == 'q') {
                    break;
                } else if (cmd == 'R' || cmd == 'r') {
                    isRestart = true;
                    break;
                } else {
                    for (MenuItem m : menuItems) {
                        if (Character.toUpperCase(cmd) == m.cmdKey) {
                            m.itemHandler.invoke(outgoingMsgFactory, writeQueueSync);
                        }
                    }
                }
                Thread.sleep(700);
            }
            isShutdown = true;
            apiInputStream.close();
            apiOutputStream.close();
            System.out.println("Shutting down connection...");
            while (tl.isAlive() || t.isAlive() || p.isAlive() || ts.isAlive()) {
                Thread.sleep(100);
            }
        } while (isRestart);
    }
}
