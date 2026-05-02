package jadx.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jadx.api.JadxDecompiler;

public class SessionManager implements AutoCloseable {
	private final Map<String, DecompilerSession> sessions = new ConcurrentHashMap<>();

	public DecompilerSession add(JadxDecompiler decompiler) {
		DecompilerSession session = new DecompilerSession(decompiler);
		sessions.put(session.getId(), session);
		return session;
	}

	public DecompilerSession get(String sessionId) {
		DecompilerSession session = sessions.get(sessionId);
		if (session == null) {
			throw new IllegalArgumentException("Session not found: " + sessionId);
		}
		session.touch();
		return session;
	}

	public boolean closeSession(String sessionId) {
		DecompilerSession session = sessions.remove(sessionId);
		if (session == null) {
			return false;
		}
		synchronized (session) {
			session.getDecompiler().close();
		}
		return true;
	}

	@Override
	public void close() {
		for (Map.Entry<String, DecompilerSession> entry : sessions.entrySet()) {
			DecompilerSession session = entry.getValue();
			synchronized (session) {
				try {
					session.getDecompiler().close();
				} catch (Exception ignored) {
					// no-op
				}
			}
		}
		sessions.clear();
	}
}
