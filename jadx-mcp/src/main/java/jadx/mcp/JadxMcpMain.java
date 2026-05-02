package jadx.mcp;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

public class JadxMcpMain {
	private static final Logger LOG = LoggerFactory.getLogger(JadxMcpMain.class);

	public static void main(String[] args) {
		try (SessionManager sessionManager = new SessionManager()) {
			McpConnection connection = new McpConnection(System.in, System.out);
			JadxMcpServer server = new JadxMcpServer(sessionManager);
			while (true) {
				JsonObject request = connection.readMessage();
				if (request == null) {
					break;
				}
				JsonObject response = server.handleRequest(request);
				if (response != null) {
					connection.writeMessage(response);
				}
			}
		} catch (IOException e) {
			LOG.error("MCP connection failed", e);
		} catch (Exception e) {
			LOG.error("Jadx MCP server failure", e);
		}
	}
}
