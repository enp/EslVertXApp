package ru.itx.esl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

public class Verticle extends AbstractVerticle {
	
	private Logger logger = LoggerFactory.getLogger(Verticle.class);
	
	private NetSocket eslSocket;
	
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
					Map<String,Object> message = new HashMap<String, Object>();
					StringBuilder body = new StringBuilder();
					for (String line : buffer.toString().split("\n")) {
						String[] keyvalue = line.split(":");
						if (keyvalue.length == 2) {
							if (keyvalue[0].trim().equals("Event-Name") || 
								keyvalue[0].trim().equals("Unique-ID") || 
								keyvalue[0].trim().equals("Content-Type"))
								message.put(keyvalue[0].trim(), keyvalue[1].trim());
						} else {
							body.append(line);
							body.append("\n");
						}
					}
					if (body.length() > 0)
						message.put("Body", body);
					logger.info("Message : " + message);
					for (ServerWebSocket webSocket : webSockets) {
						try {
							webSocket.writeTextMessage(new JsonObject(message).toString());
						} catch (Exception e) {
							webSockets.remove(webSocket);
							logger.info("Removed WebSocket : " + webSocket);
						}
					}
				}));
				command("auth ClueCon");
				//command("event text ALL");
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
	    				command(buffer.toString());	    				
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

	private void command(String command) {
		logger.info("Command : " + command);
		eslSocket.write(command+"\n\n");		
	}

}
