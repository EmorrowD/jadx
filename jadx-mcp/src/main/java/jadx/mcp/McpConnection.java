package jadx.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class McpConnection {
	private final InputStream in;
	private final OutputStream out;
	private boolean lineJsonMode;

	public McpConnection(InputStream in, OutputStream out) {
		this.in = in;
		this.out = out;
	}

	public JsonObject readMessage() throws IOException {
		int contentLength = -1;
		while (true) {
			String line = readHeaderLine();
			if (line == null) {
				return null;
			}
			String trimmedLine = line.trim();
			if (contentLength == -1 && trimmedLine.startsWith("{")) {
				lineJsonMode = true;
				return JsonParser.parseString(trimmedLine).getAsJsonObject();
			}
			if (line.isEmpty()) {
				break;
			}
			int sep = line.indexOf(':');
			if (sep == -1) {
				continue;
			}
			String headerName = line.substring(0, sep).trim();
			String headerValue = line.substring(sep + 1).trim();
			if ("Content-Length".equalsIgnoreCase(headerName)) {
				contentLength = Integer.parseInt(headerValue);
			}
		}
		if (contentLength < 0) {
			throw new IOException("Missing Content-Length header");
		}
		byte[] content = readExact(contentLength);
		return JsonParser.parseString(new String(content, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	public synchronized void writeMessage(JsonObject obj) throws IOException {
		byte[] data = obj.toString().getBytes(StandardCharsets.UTF_8);
		if (lineJsonMode) {
			out.write(data);
			out.write('\n');
			out.flush();
			return;
		}
		String header = "Content-Length: " + data.length + "\r\n\r\n";
		out.write(header.getBytes(StandardCharsets.US_ASCII));
		out.write(data);
		out.flush();
	}

	private String readHeaderLine() throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
		while (true) {
			int b = in.read();
			if (b == -1) {
				if (buf.size() == 0) {
					return null;
				}
				break;
			}
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				buf.write(b);
			}
		}
		return buf.toString(StandardCharsets.US_ASCII);
	}

	private byte[] readExact(int len) throws IOException {
		byte[] data = new byte[len];
		int pos = 0;
		while (pos < len) {
			int read = in.read(data, pos, len - pos);
			if (read == -1) {
				throw new IOException("Unexpected EOF while reading message body");
			}
			pos += read;
		}
		return data;
	}
}
