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
		"Caller-Logical-Direction",
		"Caller-Caller-ID-Number",
		"Caller-Destination-Number",
		"Playback-File-Path",
		"DTMF-Digit",
		"variable_conference_name",
		"variable_sip_history_info"
	));
	
	private Pattern patternSIPUser  = Pattern.compile("<sip:(\\+.*?)@");
	private Pattern patternWSMSISDN = Pattern.compile("^/ws/(\\+.*?)$");
	
	private String media;
	
	private List<Agent> agents = new ArrayList<Agent>();
	
	private JsonObject conf;
	
	public void start() throws Exception {
		
		conf = new JsonObject(vertx.fileSystem().readFileBlocking("config.json"));
		
		media = conf.getJsonObject("esl").getString("media", "/opt/media/");
		
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
			request.response().putHeader("content-type", "application/json").end(new JsonArray(agents).toBuffer());
		}
	}
	
	private void wsHandler(ServerWebSocket webSocket) {
		Matcher matcher = patternWSMSISDN.matcher(webSocket.path());
		if (matcher.find()) {
			Agent agent = new Agent(matcher.group(1), webSocket);
			agents.add(agent);
			webSocket.closeHandler(v -> agents.remove(agent));
			webSocket.handler(buffer -> {
				try {
					command(agent, buffer.toJsonObject());
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
			command("event text CHANNEL_PARK CHANNEL_ANSWER DTMF PLAYBACK_START PLAYBACK_STOP CHANNEL_HANGUP");
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
			ListIterator<Agent> agentsIterator = agents.listIterator();
			while (agentsIterator.hasNext()) {
				Agent agent = agentsIterator.next();
				if (eslMessageAllowed(message,agent)) {
					try {
						agent.send(new JsonObject(message).toString());
					} catch (Exception e) {
						logger.info("Removing agent : " + agent + " due to : " + e.toString());
						agentsIterator.remove();
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
				if (headers.contains(keyvalue[0].trim())) {
					try {
						message.put(keyvalue[0].trim(), URLDecoder.decode(keyvalue[1].trim(), "UTF-8"));
					} catch (UnsupportedEncodingException e) {
						logger.info("Wrong header : " + keyvalue[0].trim() + " => " + keyvalue[1].trim());
					}
				}
			} else {
				body.append(line);
				body.append("\n");
			}
		}
		if (body.length() > 0)
			message.put("Body", body);
		if (message.get("variable_conference_name") != null) {
			message.put("Conference-Name", message.get("variable_conference_name"));
			message.remove("variable_conference_name");
		}
		if (message.get("variable_sip_history_info") != null) {
			Matcher matcher = patternSIPUser.matcher(message.get("variable_sip_history_info").toString());
			if (matcher.find()) {
				message.put("Caller-History-Number", matcher.group(1));
				message.remove("variable_sip_history_info");
			}
		}
		if (message.get("Playback-File-Path") != null) {
			String path[] = message.get("Playback-File-Path").toString().split("/");
			String file = path[path.length - 1];
			message.put("Playback-File", file);
			message.remove("Playback-File-Path");	
		}
		return message;
	}
	
	private boolean eslMessageAllowed(Map<String,Object> message, Agent agent) {
		if (message.get("Event-Name").equals("CHANNEL_PARK") && message.get("Caller-Logical-Direction").equals("inbound")) {
			if (message.get("Caller-History-Number") != null && message.get("Caller-History-Number").equals(agent.getMsisdn())) {
				agent.getUuids().add(message.get("Unique-ID").toString());
				return true;
			} else {
				return false;
			}
		} else if (message.get("Event-Name").equals("CHANNEL_ANSWER") && message.get("Caller-Logical-Direction").equals("outbound")) {
			if (message.get("Caller-Caller-ID-Number") != null && message.get("Caller-Caller-ID-Number").equals(agent.getMsisdn())) {
				agent.getUuids().add(message.get("Unique-ID").toString());
				return true;
			} else {
				return false;
			}
		} else {
			if (agent.getUuids().contains(message.get("Unique-ID"))) {
				if (message.get("Event-Name").equals("CHANNEL_HANGUP"))
					agent.getUuids().remove(message.get("Unique-ID"));
				return true;
			} else {
				return false;
			}
		}
	}

	private void command(Agent agent, JsonObject command) {
		logger.info("JSON Command : " + command);
		String uuid = command.getString("uuid");
		if (uuid != null && !agent.getUuids().contains(uuid)) {
			logger.info("Wrong UUID : " + uuid);
		} else {
			String action = command.getString("action");
			String commandText = null;
			if (action.equals("answer")) {
				commandText = "api uuid_answer "+uuid;
			} else if (action.equals("playback")) {
				commandText = "api uuid_broadcast "+uuid+" playback::"+media+agent.getMsisdn()+"/"+command.getString("file");
			} else if (action.equals("hangup")) {
				commandText = "api uuid_kill "+uuid+" CALL_REJECTED";
			} else if (action.equals("call")) {
				commandText = "api originate {origination_caller_id_number="+agent.getMsisdn()+"}sofia/gateway/mss/"+command.getString("destination")+" &park()";
			} else if (action.equals("conference")) {
				commandText = "api originate {origination_caller_id_number="+agent.getMsisdn()+"}sofia/gateway/mss/"+command.getString("destination")+" &conference("+agent.getMsisdn().replace("+", "")+")";
			} 
			if (commandText != null) {
				command(commandText);
			} else {
				logger.info("Wrong JSON command : " + command);
			}
		}
	}

	private void command(String command) {
		logger.info("Text Command : " + command);
		eslSocket.write(command+"\n\n");		
	}

}
