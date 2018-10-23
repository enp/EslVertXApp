package ru.itx.esl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
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
	
	Pattern pattern = Pattern.compile("%3Csip%3A%2B(.*?)%40");
	
	private List<ServerWebSocket> webSockets = new ArrayList<ServerWebSocket>();

	public void start(Future<Void> future) throws Exception {
		
		JsonObject conf = new JsonObject(vertx.fileSystem().readFileBlocking("config.json"));
		
		String eslHost = conf.getJsonObject("esl").getString("host", "127.0.0.1");
		Integer eslPort = conf.getJsonObject("esl").getInteger("port", 8021);
		
		vertx.createNetClient().connect(eslPort, eslHost, result -> {
			if (result.succeeded()) {
				future.complete();
				eslSocket = result.result();
				eslSocket.handler(RecordParser.newDelimited("\n\n", buffer -> {
					if (buffer.toString().contains("Content-Length")) {
						eslBuffer = buffer;
					} else {
						Map<String,Object> message = new HashMap<String, Object>();
						StringBuilder body = new StringBuilder();
						if (eslBuffer != null) {
							buffer = eslBuffer.appendBuffer(Buffer.buffer("\n")).appendBuffer(buffer);
							eslBuffer = null;
						}
						for (String line : buffer.toString().split("\n")) {
							String[] keyvalue = line.split(":");
							if (keyvalue.length == 2) {
								if (headers.contains(keyvalue[0].trim()))
									message.put(keyvalue[0].trim(), keyvalue[1].trim().replace("%2B", ""));
							} else {
								body.append(line);
								body.append("\n");
							}
						}
						if (body.length() > 0)
							message.put("Body", body);
						if (message.get("variable_sip_history_info") != null) {
							Matcher matcher = pattern.matcher(message.get("variable_sip_history_info").toString());
							if (matcher.find()) {
								message.put("Caller-History-Number", matcher.group(1));
								message.remove("variable_sip_history_info");
							}
						}
						logger.info("Message : " + message);
						for (ServerWebSocket webSocket : webSockets) {
							try {
								if (webSocket != null)
									webSocket.writeTextMessage(new JsonObject(message).toString());
							} catch (Exception e) {
								logger.info("Removing WebSocket : " + webSocket + " due to : " + e.toString());
								webSockets.remove(webSocket);
							}
						}
					}
				}));
				command("auth ClueCon");
				command("event text CHANNEL_PARK CHANNEL_ANSWER PLAYBACK_START PLAYBACK_STOP CHANNEL_HANGUP CHANNEL_HANGUP_COMPLETE");
			} else {
				future.fail(result.cause());
			}
		});
		
		String webHost = conf.getJsonObject("web").getString("host", "127.0.0.1");
		Integer webPort = conf.getJsonObject("web").getInteger("port", 8000);
		
		vertx.createHttpServer()
	    	.websocketHandler(webSocket -> {
	    		webSockets.add(webSocket);
	    		webSocket.handler(buffer -> {
	    			try {
	    				command(buffer.toJsonObject());
					} catch (Exception e) {
						logger.error(e);
					}
	    		});
	    	})
			.requestHandler(request -> {
				if (request.uri().equals("/"))
	    			request.response().end(vertx.fileSystem().readFileBlocking("websocket.html"));
			})
			.listen(webPort, webHost);
	}

	private void command(JsonObject command) {
		String uuid = command.getString("uuid");
		String action = command.getString("action");
		String commandText = null;
		if (action.equals("answer")) {
			commandText = "api uuid_answer "+uuid;
		} else if (action.equals("playback")) {
			commandText = "api uuid_broadcast "+uuid+" playback::/opt/sounds/"+command.getString("file");
		} else if (action.equals("hangup")) {
			commandText = "api uuid_kill "+uuid+" CALL_REJECTED";
		} 
		if (commandText != null)
			command(commandText);
		else
			logger.info("Wrong JSON command : " + command);
	}

	private void command(String command) {
		logger.info("Command : " + command);
		eslSocket.write(command+"\n\n");		
	}

}
