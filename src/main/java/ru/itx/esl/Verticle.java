package ru.itx.esl;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

public class Verticle extends AbstractVerticle {
	
	private Logger logger = LoggerFactory.getLogger(Verticle.class);
	
	private NetSocket eslSocket;
	
	private Buffer eslBuffer;
	
	private List<String> headers = new ArrayList<String>(Arrays.asList(
		"Content-Type",
		"Event-Name",
		"Unique-ID",
		"Caller-Caller-ID-Number",
		"Caller-Destination-Number",
		"variable_sip_history_info"
	));
	
	private Pattern patternSIPUser  = Pattern.compile("<sip:(\\+.*?)@");
	private Pattern patternWSMSISDN = Pattern.compile("^/ws/(\\+.*?)$");
	
	private List<ServerWebSocket> webSockets = new ArrayList<ServerWebSocket>();
	
	private JsonObject conf;

	public void start() throws Exception {
		
		conf = new JsonObject(vertx.fileSystem().readFileBlocking("config.json"));
		
		String eslHost = conf.getJsonObject("esl").getString("host", "127.0.0.1");
		Integer eslPort = conf.getJsonObject("esl").getInteger("port", 8021);
		
		vertx.createNetClient().connect(eslPort, eslHost, this::eslHandler);
		
		String webHost = conf.getJsonObject("web").getString("host", "127.0.0.1");
		Integer webPort = conf.getJsonObject("web").getInteger("port", 8000);
		
		vertx.createHttpServer().websocketHandler(this::wsHandler).requestHandler(this::httpHandler).listen(webPort, webHost);
	}
	
	private void httpHandler(HttpServerRequest request) {
		if (request.uri().equals("/")) {
			request.response().end(vertx.fileSystem().readFileBlocking("websocket.html"));
		} else if (request.uri().equals("/sockets")) {
			JsonArray response = new JsonArray();
			for (ServerWebSocket webSocket : webSockets)
				response.add(new JsonObject()
					.put("path", webSocket.path().replace("/ws/", ""))
					.put("remoteAddress", webSocket.remoteAddress().toString())
				);
			request.response().putHeader("content-type", "application/json").end(response.toBuffer());
		}
	}
	
	private void wsHandler(ServerWebSocket webSocket) {
		Matcher matcher = patternWSMSISDN.matcher(webSocket.path());
		if (matcher.find()) {
			webSockets.add(webSocket);
			webSocket.handler(buffer -> {
				try {
					command(webSocket, buffer.toJsonObject());
				} catch (Exception e) {
					logger.error(e);
				}
			});
		} else {
			webSocket.reject(404);
		}
	}
	
	private void eslHandler(AsyncResult<NetSocket> result) {
		if (result.succeeded()) {
			eslSocket = result.result();
			eslSocket.handler(RecordParser.newDelimited("\n\n", this::eslMessagesHandler));
			command("auth "+conf.getJsonObject("esl").getString("auth", "ClueCon"));
			command("event text CHANNEL_PARK CHANNEL_ANSWER PLAYBACK_START PLAYBACK_STOP CHANNEL_HANGUP CHANNEL_HANGUP_COMPLETE");
		}
	}
	
	private void eslMessagesHandler(Buffer buffer) {
		if (buffer.toString().contains("Content-Length")) {
			eslBuffer = buffer;
		} else {
			if (eslBuffer != null) {
				buffer = eslBuffer.appendBuffer(Buffer.buffer("\n")).appendBuffer(buffer);
				eslBuffer = null;
			}
			Map<String,Object> message = eslMessageParser(buffer);
			logger.info("Text Message : " + message);
			ListIterator<ServerWebSocket> webSocketIterator = webSockets.listIterator();
			while (webSocketIterator.hasNext()) {
				ServerWebSocket webSocket = webSocketIterator.next();
				if (eslMessageAllowed(message,webSocket)) {
					try {
						webSocket.writeTextMessage(new JsonObject(message).toString());
					} catch (Exception e) {
						logger.info("Removing WebSocket : " + webSocket.path() + "::" + webSocket.textHandlerID() + " due to : " + e.toString());
						webSocketIterator.remove();
					}
				}
			}
		}
	}
	
	private Map<String,Object> eslMessageParser(Buffer buffer) {
		Map<String,Object> message = new HashMap<String, Object>();
		StringBuilder body = new StringBuilder();
		for (String line : buffer.toString().split("\n")) {
			String[] keyvalue = line.split(":");
			if (keyvalue.length == 2) {
				if (headers.contains(keyvalue[0].trim()))
					try {
						message.put(keyvalue[0].trim(), URLDecoder.decode(keyvalue[1].trim(), "UTF-8"));
					} catch (UnsupportedEncodingException e) {
						logger.info("Wrong header : " + keyvalue[0].trim() + " => " + keyvalue[1].trim());
					}
			} else {
				body.append(line);
				body.append("\n");
			}
		}
		if (body.length() > 0)
			message.put("Body", body);
		if (message.get("variable_sip_history_info") != null) {
			Matcher matcher = patternSIPUser.matcher(message.get("variable_sip_history_info").toString());
			if (matcher.find()) {
				message.put("Caller-History-Number", matcher.group(1));
				message.remove("variable_sip_history_info");
			}
		}
		return message;
	}
	
	private boolean eslMessageAllowed(Map<String,Object> message, ServerWebSocket webSocket) {
		String msisdn = webSocket.path().replace("/ws/", "");
		if (message.get("Event-Name").equals("CHANNEL_PARK") && message.get("Caller-History-Number").equals(msisdn)) {
			// TODO: associate Unique-ID of incoming call with WebSocket
			return true;
		} else if (message.get("Event-Name").equals("CHANNEL_ANSWER") && message.get("Caller-Caller-ID-Number").equals(msisdn)) {
			// TODO: associate Unique-ID of outgoing call with WebSocket
			return true;
		} else {
			// TODO: check if Unique-ID of call associated with WebSocket
			return true;
		}
	}

	private void command(ServerWebSocket webSocket, JsonObject command) {
		logger.info("JSON Command : " + command);
		// TODO: check if Unique-ID associated with WebSocket
		String uuid = command.getString("uuid");
		String action = command.getString("action");
		String commandText = null;
		if (action.equals("answer")) {
			commandText = "api uuid_answer "+uuid;
		} else if (action.equals("playback")) {
			commandText = "api uuid_broadcast "+uuid+" playback::/opt/sounds/"+command.getString("file");
		} else if (action.equals("hangup")) {
			commandText = "api uuid_kill "+uuid+" CALL_REJECTED";
		} else if (action.equals("call")) {
			String caller = webSocket.path().replace("/ws/", "");
			commandText = "api originate {origination_caller_id_number="+caller+"}sofia/gateway/mss/"+command.getString("destination")+" &park()";
		} 
		if (commandText != null)
			command(commandText);
		else
			logger.info("Wrong JSON command : " + command);
	}

	private void command(String command) {
		logger.info("Text Command : " + command);
		eslSocket.write(command+"\n\n");		
	}

}
