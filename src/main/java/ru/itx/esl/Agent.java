package ru.itx.esl;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.http.ServerWebSocket;

class Agent {

	private String msisdn;
	private ServerWebSocket webSocket;

	private List<String> uuids = new ArrayList<String>();
	
	public Agent(String msisdn, ServerWebSocket webSocket) {
		this.msisdn = msisdn;
		this.webSocket = webSocket;
	}
	
	public String getMsisdn() {
		return msisdn;
	}
	
	public String getRemoteAddress() {
		return webSocket.remoteAddress().toString();
	}

	public List<String> getUuids() {
		return uuids;
	}
	
	public void send(String text) {
		webSocket.writeTextMessage(text);
	}

	public String toString() {
		return "[" + getMsisdn() + "::" + getRemoteAddress() + "]";
	}
	
	
}