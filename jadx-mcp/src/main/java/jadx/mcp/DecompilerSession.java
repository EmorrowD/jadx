package jadx.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;

public class DecompilerSession {
	private final String id = UUID.randomUUID().toString();
	private final JadxDecompiler decompiler;
	private final long createdAt = System.currentTimeMillis();
	private volatile long lastAccessAt = createdAt;

	private final Map<String, JavaClass> classesByOrigName = new HashMap<>();
	private final Map<String, JavaClass> classesByAliasName = new HashMap<>();
	private final Map<String, JavaClass> classesByRawName = new HashMap<>();
	private final Map<String, List<JavaClass>> classesByShortName = new HashMap<>();
	private final Map<String, ResourceFile> resourcesByOrigName = new LinkedHashMap<>();
	private final Map<String, ResourceFile> resourcesByDeobfName = new LinkedHashMap<>();

	public DecompilerSession(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
		rebuildIndexes();
	}

	public String getId() {
		return id;
	}

	public JadxDecompiler getDecompiler() {
		return decompiler;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public long getLastAccessAt() {
		return lastAccessAt;
	}

	public void touch() {
		lastAccessAt = System.currentTimeMillis();
	}

	public void rebuildIndexes() {
		classesByOrigName.clear();
		classesByAliasName.clear();
		classesByRawName.clear();
		classesByShortName.clear();
		for (JavaClass cls : decompiler.getClassesWithInners()) {
			classesByOrigName.put(cls.getClassNode().getClassInfo().getFullName(), cls);
			classesByAliasName.put(cls.getClassNode().getClassInfo().getAliasFullName(), cls);
			classesByRawName.put(cls.getRawName(), cls);
			classesByShortName.computeIfAbsent(cls.getName(), k -> new ArrayList<>()).add(cls);
		}
		resourcesByOrigName.clear();
		resourcesByDeobfName.clear();
		for (ResourceFile resource : decompiler.getResources()) {
			resourcesByOrigName.put(resource.getOriginalName(), resource);
			resourcesByDeobfName.put(resource.getDeobfName(), resource);
		}
	}

	public @Nullable JavaClass resolveClass(String name, String naming) {
		switch (naming) {
			case "orig":
				return classesByOrigName.get(name);
			case "alias":
				return classesByAliasName.get(name);
			case "raw":
				return classesByRawName.get(name);
			case "auto":
			default:
				JavaClass found = classesByOrigName.get(name);
				if (found != null) {
					return found;
				}
				found = classesByAliasName.get(name);
				if (found != null) {
					return found;
				}
				found = classesByRawName.get(name);
				if (found != null) {
					return found;
				}
				List<JavaClass> byShort = classesByShortName.get(name);
				if (byShort != null && byShort.size() == 1) {
					return byShort.get(0);
				}
				return null;
		}
	}

	public List<JavaClass> listClasses(boolean includeInners) {
		if (includeInners) {
			return decompiler.getClassesWithInners();
		}
		return decompiler.getClasses();
	}

	public Map<String, JavaClass> getClassesByOrigName() {
		return Collections.unmodifiableMap(classesByOrigName);
	}

	public List<JavaClass> allClasses() {
		return new ArrayList<>(classesByOrigName.values());
	}

	public @Nullable ResourceFile resolveResource(String name) {
		ResourceFile byOrig = resourcesByOrigName.get(name);
		if (byOrig != null) {
			return byOrig;
		}
		ResourceFile byDeobf = resourcesByDeobfName.get(name);
		if (byDeobf != null) {
			return byDeobf;
		}
		List<ResourceFile> partial = resourcesByDeobfName.entrySet().stream()
				.filter(e -> e.getKey().contains(name))
				.map(Map.Entry::getValue)
				.collect(Collectors.toList());
		if (partial.size() == 1) {
			return partial.get(0);
		}
		return null;
	}
}
