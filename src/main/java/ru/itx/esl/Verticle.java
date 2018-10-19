package ru.itx.esl;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

public class Verticle extends AbstractVerticle {
	
	Logger logger = LoggerFactory.getLogger(Verticle.class);

	public void start(Future<Void> future) throws Exception {
		JsonObject conf = new JsonObject(new String(Files.readAllBytes(Paths.get("config.json"))));
		String eslHost = conf.getJsonObject("esl").getString("host", "127.0.0.1");
		Integer eslPort = conf.getJsonObject("esl").getInteger("port", 1025);
		vertx.createNetClient().connect(eslPort, eslHost, result -> {
			if (result.succeeded()) {
				future.complete();
				NetSocket socket = result.result();
				socket.handler(RecordParser.newDelimited("\n\n", buffer -> {
					Map<String,String> message = new HashMap<String, String>();
					for (String line : buffer.toString().split("\n")) {
						String[] keyvalue = line.split(":");
						message.put(keyvalue[0].trim(), keyvalue[1].trim());
					}
					logger.info("Message: " + message);
				}));
				command(socket, "auth ClueCon");
				command(socket, "event text ALL");
			} else {
				future.fail(result.cause());
			}
		});
	}

	private void command(NetSocket socket, String command) {
		logger.info("Command: " + command);
		socket.write(command+"\n\n");		
	}

}
