package jadx.mcp;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.JavaPackage;
import jadx.api.ResourceFile;
import jadx.api.plugins.options.JadxPluginOptions;
import jadx.api.plugins.options.OptionDescription;
import jadx.core.plugins.PluginContext;
import jadx.core.utils.ErrorsCounter;
import jadx.core.xmlgen.ResContainer;
import jadx.plugins.tools.JadxPluginsTools;
import jadx.plugins.tools.data.JadxPluginMetadata;
import jadx.plugins.tools.data.JadxPluginUpdate;

public class JadxMcpServer {
	private static final String PROTOCOL_VERSION = "2024-11-05";
	private static final int DEFAULT_LIMIT = 100;

	private final SessionManager sessionManager;
	private final JsonArray toolsSchema;

	public JadxMcpServer(SessionManager sessionManager) {
		this.sessionManager = sessionManager;
		this.toolsSchema = buildToolsSchema();
	}

	public JsonObject handleRequest(JsonObject request) {
		String method = getAsString(request, "method", "");
		JsonElement id = request.get("id");
		JsonObject params = getAsObject(request, "params");
		switch (method) {
			case "initialize":
				return success(id, buildInitializeResult(params));
			case "notifications/initialized":
				return null;
			case "ping":
				return success(id, new JsonObject());
			case "tools/list":
				JsonObject toolsResult = new JsonObject();
				toolsResult.add("tools", toolsSchema);
				return success(id, toolsResult);
			case "tools/call":
				return success(id, callTool(params));
			default:
				return error(id, -32601, "Method not found: " + method);
		}
	}

	private JsonObject callTool(JsonObject params) {
		String name = getAsString(params, "name", "");
		JsonObject args = getAsObject(params, "arguments");
		try {
			JsonObject result;
			switch (name) {
				case "session_open":
					result = toolSessionOpen(args);
					break;
				case "session_close":
					result = toolSessionClose(args);
					break;
				case "session_info":
					result = toolSessionInfo(args);
					break;
				case "list_classes":
					result = toolListClasses(args);
					break;
				case "get_class_source":
					result = toolGetClassSource(args);
					break;
				case "get_class_smali":
					result = toolGetClassSmali(args);
					break;
				case "get_method_source":
					result = toolGetMethodSource(args);
					break;
				case "resolve_symbol":
					result = toolResolveSymbol(args);
					break;
				case "find_usages":
					result = toolFindUsages(args);
					break;
				case "find_method_calls":
					result = toolFindMethodCalls(args);
					break;
				case "search_code":
					result = toolSearchCode(args);
					break;
			case "search_symbols":
				result = toolSearchSymbols(args);
				break;
			case "get_class_deep":
				result = toolGetClassDeep(args);
				break;
			case "search_and_resolve":
				result = toolSearchAndResolve(args);
				break;
			case "get_android_manifest":
				result = toolGetAndroidManifest(args);
				break;
			case "get_main_activity":
				result = toolGetMainActivity(args);
				break;
			case "get_app_classes":
				result = toolGetAppClasses(args);
				break;
			case "get_strings_resource":
				result = toolGetStringsResource(args);
				break;
			case "list_resources":
					result = toolListResources(args);
					break;
				case "get_resource_content":
					result = toolGetResourceContent(args);
					break;
				case "get_errors_report":
					result = toolGetErrorsReport(args);
					break;
				case "export_project":
					result = toolExportProject(args);
					break;
				case "list_plugins":
					result = toolListPlugins(args);
					break;
				case "list_plugin_options":
					result = toolListPluginOptions(args);
					break;
				case "set_plugin_option":
					result = toolSetPluginOption(args);
					break;
				case "plugins_install":
					result = toolPluginsInstall(args);
					break;
				case "plugins_update_all":
					result = toolPluginsUpdateAll();
					break;
				case "plugins_uninstall":
					result = toolPluginsUninstall(args);
					break;
				default:
					throw new IllegalArgumentException("Unknown tool: " + name);
			}
			return toolSuccess(result);
		} catch (Exception e) {
			return toolError(e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private JsonObject toolSessionOpen(JsonObject argsObj) {
		JsonObject options = getAsObject(argsObj, "options");
		JsonObject pluginOptions = getAsObject(argsObj, "plugin_options");
		JadxArgs args = JadxArgsMapper.buildArgs(argsObj, pluginOptions);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		try {
			decompiler.load();
			DecompilerSession session = sessionManager.add(decompiler);
			synchronized (session) {
				JsonObject out = new JsonObject();
				out.addProperty("session_id", session.getId());
				out.addProperty("created_at", session.getCreatedAt());
				out.addProperty("classes_count", decompiler.getClasses().size());
				out.addProperty("classes_with_inners_count", decompiler.getClassesWithInners().size());
				out.addProperty("resources_count", decompiler.getResources().size());
				out.addProperty("errors_count", decompiler.getErrorsCount());
				out.addProperty("warns_count", decompiler.getWarnsCount());
				out.add("options", options == null ? JsonNull.INSTANCE : options.deepCopy());
				return out;
			}
		} catch (Exception e) {
			decompiler.close();
			throw e;
		}
	}

	private JsonObject toolSessionClose(JsonObject argsObj) {
		String sessionId = requireString(argsObj, "session_id");
		boolean closed = sessionManager.closeSession(sessionId);
		JsonObject out = new JsonObject();
		out.addProperty("session_id", sessionId);
		out.addProperty("closed", closed);
		return out;
	}

	private JsonObject toolSessionInfo(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		synchronized (session) {
			JadxDecompiler decompiler = session.getDecompiler();
			JsonObject out = new JsonObject();
			out.addProperty("session_id", session.getId());
			out.addProperty("created_at", session.getCreatedAt());
			out.addProperty("last_access_at", session.getLastAccessAt());
			out.addProperty("classes_count", decompiler.getClasses().size());
			out.addProperty("classes_with_inners_count", decompiler.getClassesWithInners().size());
			out.addProperty("resources_count", decompiler.getResources().size());
			out.addProperty("errors_count", decompiler.getErrorsCount());
			out.addProperty("warns_count", decompiler.getWarnsCount());
			JsonObject input = new JsonObject();
			JsonArray files = new JsonArray();
			for (File file : decompiler.getArgs().getInputFiles()) {
				files.add(file.getAbsolutePath());
			}
			input.add("input_files", files);
			out.add("input", input);
			return out;
		}
	}

	private JsonObject toolListClasses(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		boolean includeInners = getAsBoolean(argsObj, "include_inners", false);
		String packagePrefix = getAsString(argsObj, "package_prefix", "");
		String nameQuery = getAsString(argsObj, "name_query", "");
		boolean regex = getAsBoolean(argsObj, "regex", false);
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		Pattern pattern = buildPattern(nameQuery, regex, getAsBoolean(argsObj, "ignore_case", true));

		synchronized (session) {
			List<JavaClass> classes = session.listClasses(includeInners);
			JsonArray list = new JsonArray();
			int matchIndex = 0;
			for (JavaClass cls : classes) {
				if (!packagePrefix.isEmpty() && !cls.getPackage().startsWith(packagePrefix)) {
					continue;
				}
				if (pattern != null && !matchesClass(pattern, cls)) {
					continue;
				}
				if (matchIndex >= offset && list.size() < limit) {
					list.add(classToJson(cls));
				}
				matchIndex++;
			}
			JsonObject out = new JsonObject();
			out.add("classes", list);
			out.addProperty("count", list.size());
			out.addProperty("total", matchIndex);
			out.addProperty("offset", offset);
			out.addProperty("has_more", offset + list.size() < matchIndex);
			return out;
		}
	}

	private JsonObject toolGetClassSource(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String naming = getAsString(argsObj, "naming", "auto");
		JsonElement classNamesElem = argsObj.get("class_names");
		if (classNamesElem != null && classNamesElem.isJsonArray()) {
			JsonArray classNamesArr = classNamesElem.getAsJsonArray();
			synchronized (session) {
				JsonArray classes = new JsonArray();
				for (JsonElement elem : classNamesArr) {
					String cn = elem.getAsString();
					JavaClass cls = session.resolveClass(cn, naming);
					JsonObject item = new JsonObject();
					if (cls != null) {
						item.add("class", classToJson(cls));
						item.addProperty("code", cls.getCode());
						item.addProperty("found", true);
					} else {
						item.addProperty("class_name", cn);
						item.addProperty("found", false);
						item.addProperty("error", "Class not found: " + cn);
					}
					classes.add(item);
				}
				JsonObject out = new JsonObject();
				out.add("classes", classes);
				out.addProperty("count", classes.size());
				return out;
			}
		}
		String className = requireString(argsObj, "class_name");
		synchronized (session) {
			JavaClass cls = requireClass(session, className, naming);
			JsonObject out = new JsonObject();
			out.add("class", classToJson(cls));
			out.addProperty("code", cls.getCode());
			return out;
		}
	}

	private JsonObject toolGetClassSmali(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String className = requireString(argsObj, "class_name");
		String naming = getAsString(argsObj, "naming", "auto");
		synchronized (session) {
			JavaClass cls = requireClass(session, className, naming);
			JsonObject out = new JsonObject();
			out.add("class", classToJson(cls));
			out.addProperty("smali", cls.getSmali());
			return out;
		}
	}

	private JsonObject toolGetMethodSource(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String className = requireString(argsObj, "class_name");
		String naming = getAsString(argsObj, "naming", "auto");
		String methodShortId = requireString(argsObj, "method_short_id");
		synchronized (session) {
			JavaClass cls = requireClass(session, className, naming);
			JavaMethod method = cls.searchMethodByShortId(methodShortId);
			if (method == null) {
				throw new IllegalArgumentException("Method not found by short id: " + methodShortId);
			}
			JsonObject out = new JsonObject();
			out.add("class", classToJson(cls));
			out.add("method", methodToJson(method));
			out.addProperty("code", method.getCodeStr());
			return out;
		}
	}

	private JsonObject toolResolveSymbol(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String query = requireString(argsObj, "query");
		String kind = getAsString(argsObj, "kind", "all").toLowerCase(Locale.ROOT);
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		boolean ignoreCase = getAsBoolean(argsObj, "ignore_case", true);
		String cmpQuery = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;

		synchronized (session) {
			JsonArray matches = new JsonArray();
			int matchIndex = 0;
			if ("all".equals(kind) || "class".equals(kind)) {
				JavaClass exact = session.resolveClass(query, "auto");
				if (exact != null) {
					if (matchIndex >= offset && matches.size() < limit) {
						matches.add(classToJson(exact));
					}
					matchIndex++;
				}
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					if (containsIgnoreCase(cls.getFullName(), cls.getRawName(), cls.getName(), cmpQuery, ignoreCase)) {
						if (matchIndex >= offset) {
							matches.add(classToJson(cls));
						}
						matchIndex++;
					}
				}
			}
			if ("all".equals(kind) || "method".equals(kind)) {
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					for (JavaMethod method : cls.getMethods()) {
						if (matches.size() >= limit) {
							break;
						}
						if (containsIgnoreCase(method.getFullName(), method.getName(),
								method.getMethodNode().getMethodInfo().getShortId(), cmpQuery, ignoreCase)) {
							if (matchIndex >= offset) {
								matches.add(methodToJson(method));
							}
							matchIndex++;
						}
					}
				}
			}
			if ("all".equals(kind) || "field".equals(kind)) {
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					for (JavaField field : cls.getFields()) {
						if (matches.size() >= limit) {
							break;
						}
						if (containsIgnoreCase(field.getFullName(), field.getName(), field.getRawName(), cmpQuery, ignoreCase)) {
							if (matchIndex >= offset) {
								matches.add(fieldToJson(field));
							}
							matchIndex++;
						}
					}
				}
			}
			JsonObject out = new JsonObject();
			out.add("matches", matches);
			out.addProperty("count", matches.size());
			out.addProperty("offset", offset);
			out.addProperty("has_more", matches.size() >= limit);
			return out;
		}
	}

	private JsonObject toolFindUsages(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		JsonObject symbol = getAsObject(argsObj, "symbol");
		if (symbol == null) {
			throw new IllegalArgumentException("Missing required argument: symbol");
		}
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		synchronized (session) {
			JavaNode node = resolveNode(session, symbol);
			List<JavaNode> useIn = node.getUseIn();
			int total = useIn.size();
			int fromIdx = Math.min(offset, total);
			int toIdx = Math.min(offset + limit, total);
			JsonArray usage = new JsonArray();
			for (JavaNode usedBy : useIn.subList(fromIdx, toIdx)) {
				usage.add(nodeToJson(usedBy));
			}
			JsonObject out = new JsonObject();
			out.add("symbol", nodeToJson(node));
			out.add("usages", usage);
			out.addProperty("count", usage.size());
			out.addProperty("total", total);
			out.addProperty("offset", offset);
			out.addProperty("has_more", toIdx < total);
			return out;
		}
	}

	private JsonObject toolFindMethodCalls(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String className = requireString(argsObj, "class_name");
		String naming = getAsString(argsObj, "naming", "auto");
		String methodShortId = requireString(argsObj, "method_short_id");
		synchronized (session) {
			JavaClass cls = requireClass(session, className, naming);
			JavaMethod method = cls.searchMethodByShortId(methodShortId);
			if (method == null) {
				throw new IllegalArgumentException("Method not found by short id: " + methodShortId);
			}
			JsonArray called = new JsonArray();
			for (JavaNode calledNode : method.getUsed()) {
				called.add(nodeToJson(calledNode));
			}
			JsonArray unresolved = new JsonArray();
			method.getUnresolvedUsed().forEach(ref -> unresolved.add(ref.toString()));
			JsonArray calledFrom = new JsonArray();
			for (JavaNode usedBy : method.getUseIn()) {
				calledFrom.add(nodeToJson(usedBy));
			}
			JsonObject out = new JsonObject();
			out.add("method", methodToJson(method));
			out.add("calls", called);
			out.add("called_from", calledFrom);
			out.add("unresolved_calls", unresolved);
			out.addProperty("calls_self", method.callsSelf());
			return out;
		}
	}

	private JsonObject toolSearchCode(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String query = requireString(argsObj, "query");
		boolean regex = getAsBoolean(argsObj, "regex", false);
		boolean ignoreCase = getAsBoolean(argsObj, "ignore_case", true);
		String packagePrefix = getAsString(argsObj, "package_prefix", "");
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		boolean includeSource = getAsBoolean(argsObj, "include_source", false);
		Pattern pattern = buildPattern(query, regex, ignoreCase);
		synchronized (session) {
			JsonArray results = new JsonArray();
			Map<String, JavaClass> sourcesInWindow = new LinkedHashMap<>();
			int matchCounter = 0;
			boolean hasMore = false;
			scanLoop:
			for (JavaClass cls : session.allClasses()) {
				if (!packagePrefix.isEmpty() && !cls.getPackage().startsWith(packagePrefix)) {
					continue;
				}
				String code = cls.getCode();
				if (regex) {
					Matcher matcher = pattern.matcher(code);
					while (matcher.find()) {
						if (matchCounter >= offset) {
							if (results.size() < limit) {
								results.add(codeMatchToJson(cls, code, matcher.start(), matcher.end()));
								if (includeSource) {
									sourcesInWindow.put(cls.getFullName(), cls);
								}
							} else {
								hasMore = true;
								break scanLoop;
							}
						}
						matchCounter++;
					}
				} else {
					String src = ignoreCase ? code.toLowerCase(Locale.ROOT) : code;
					String needle = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;
					int idx = src.indexOf(needle);
					while (idx != -1) {
						if (matchCounter >= offset) {
							if (results.size() < limit) {
								results.add(codeMatchToJson(cls, code, idx, idx + needle.length()));
								if (includeSource) {
									sourcesInWindow.put(cls.getFullName(), cls);
								}
							} else {
								hasMore = true;
								break scanLoop;
							}
						}
						matchCounter++;
						idx = src.indexOf(needle, idx + Math.max(1, needle.length()));
					}
				}
			}
			JsonObject out = new JsonObject();
			out.add("results", results);
			out.addProperty("count", results.size());
			out.addProperty("offset", offset);
			out.addProperty("has_more", hasMore);
			if (includeSource) {
				JsonObject sources = new JsonObject();
				for (Map.Entry<String, JavaClass> entry : sourcesInWindow.entrySet()) {
					sources.addProperty(entry.getKey(), entry.getValue().getCode());
				}
				out.add("sources", sources);
			}
			return out;
		}
	}

	private JsonObject toolSearchSymbols(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String query = requireString(argsObj, "query");
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		boolean regex = getAsBoolean(argsObj, "regex", false);
		boolean ignoreCase = getAsBoolean(argsObj, "ignore_case", true);
		boolean includeSource = getAsBoolean(argsObj, "include_source", false);
		Set<String> kinds = getKinds(argsObj);
		Pattern pattern = buildPattern(query, regex, ignoreCase);

		synchronized (session) {
			JsonArray matches = new JsonArray();
			Map<String, JavaClass> sourcesInWindow = new LinkedHashMap<>();
			int matchCounter = 0;
			boolean hasMore = false;
			symbolLoop:
			for (JavaClass cls : session.allClasses()) {
				boolean classMatched = false;
				if (kinds.contains("class") && matchesPattern(pattern, cls.getFullName(), cls.getName(), cls.getRawName(), query, ignoreCase)) {
					if (matchCounter >= offset) {
						if (matches.size() < limit) {
							matches.add(classToJson(cls));
							classMatched = true;
						} else {
							hasMore = true;
							break symbolLoop;
						}
					} else {
						classMatched = true;
					}
					matchCounter++;
				}
				if (kinds.contains("method")) {
					for (JavaMethod method : cls.getMethods()) {
						if (matchesPattern(pattern, method.getFullName(), method.getName(),
								method.getMethodNode().getMethodInfo().getShortId(), query, ignoreCase)) {
							if (matchCounter >= offset) {
								if (matches.size() < limit) {
									matches.add(methodToJson(method));
									classMatched = true;
								} else {
									hasMore = true;
									break symbolLoop;
								}
							} else {
								classMatched = true;
							}
							matchCounter++;
						}
					}
				}
				if (kinds.contains("field")) {
					for (JavaField field : cls.getFields()) {
						if (matchesPattern(pattern, field.getFullName(), field.getName(), field.getRawName(), query, ignoreCase)) {
							if (matchCounter >= offset) {
								if (matches.size() < limit) {
									matches.add(fieldToJson(field));
									classMatched = true;
								} else {
									hasMore = true;
									break symbolLoop;
								}
							} else {
								classMatched = true;
							}
							matchCounter++;
						}
					}
				}
				if (kinds.contains("package")) {
					JavaPackage pkg = cls.getJavaPackage();
					if (pkg != null && matchesPattern(pattern, pkg.getFullName(), pkg.getRawFullName(), pkg.getName(), query, ignoreCase)) {
						if (matchCounter >= offset && matches.size() < limit) {
							matches.add(packageToJson(pkg));
						}
						matchCounter++;
					}
				}
				if (includeSource && classMatched && !sourcesInWindow.containsKey(cls.getFullName())) {
					sourcesInWindow.put(cls.getFullName(), cls);
				}
			}
			JsonObject out = new JsonObject();
			out.add("matches", matches);
			out.addProperty("count", matches.size());
			out.addProperty("offset", offset);
			out.addProperty("has_more", hasMore);
			if (includeSource) {
				JsonObject sources = new JsonObject();
				for (Map.Entry<String, JavaClass> entry : sourcesInWindow.entrySet()) {
					sources.addProperty(entry.getKey(), entry.getValue().getCode());
				}
				out.add("sources", sources);
			}
			return out;
		}
	}

	private JsonObject toolGetClassDeep(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String className = requireString(argsObj, "class_name");
		String naming = getAsString(argsObj, "naming", "auto");
		boolean includeUsages = getAsBoolean(argsObj, "include_usages", true);
		boolean includeMethodCalls = getAsBoolean(argsObj, "include_method_calls", false);
		boolean includeInnersSource = getAsBoolean(argsObj, "include_inners_source", false);
		synchronized (session) {
			JavaClass cls = requireClass(session, className, naming);
			JsonObject out = new JsonObject();
			out.add("class", classToJson(cls));
			out.addProperty("source", cls.getCode());

			JsonArray fields = new JsonArray();
			for (JavaField field : cls.getFields()) {
				fields.add(fieldToJson(field));
			}
			out.add("fields", fields);

			JsonArray methods = new JsonArray();
			for (JavaMethod method : cls.getMethods()) {
				JsonObject m = methodToJson(method);
				if (includeMethodCalls) {
					JsonArray calls = new JsonArray();
					for (JavaNode calledNode : method.getUsed()) {
						calls.add(nodeToJson(calledNode));
					}
					m.add("calls", calls);
					JsonArray calledFrom = new JsonArray();
					for (JavaNode usedBy : method.getUseIn()) {
						calledFrom.add(nodeToJson(usedBy));
					}
					m.add("called_from", calledFrom);
					m.addProperty("calls_self", method.callsSelf());
				}
				methods.add(m);
			}
			out.add("methods", methods);

			if (includeUsages) {
				JsonArray usages = new JsonArray();
				for (JavaNode usedBy : cls.getUseIn()) {
					usages.add(nodeToJson(usedBy));
				}
				out.add("usages", usages);
				out.addProperty("usages_count", usages.size());
			}

			JsonArray innerClasses = new JsonArray();
			for (JavaClass inner : cls.getInnerClasses()) {
				if (includeInnersSource) {
					JsonObject innerObj = new JsonObject();
					innerObj.add("class", classToJson(inner));
					innerObj.addProperty("source", inner.getCode());
					innerClasses.add(innerObj);
				} else {
					innerClasses.add(classToJson(inner));
				}
			}
			out.add("inner_classes", innerClasses);
			out.addProperty("inner_classes_count", innerClasses.size());

			return out;
		}
	}

	private JsonObject toolSearchAndResolve(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String query = requireString(argsObj, "query");
		String kind = getAsString(argsObj, "kind", "all").toLowerCase(Locale.ROOT);
		int limit = getAsInt(argsObj, "limit", 10);
		boolean ignoreCase = getAsBoolean(argsObj, "ignore_case", true);
		boolean includeSource = getAsBoolean(argsObj, "include_source", true);
		String cmpQuery = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;

		synchronized (session) {
			JsonArray matches = new JsonArray();
			Map<String, JavaClass> sourceClasses = new LinkedHashMap<>();

			if ("all".equals(kind) || "class".equals(kind)) {
				JavaClass exact = session.resolveClass(query, "auto");
				if (exact != null && matches.size() < limit) {
					matches.add(classToJson(exact));
					if (includeSource) {
						sourceClasses.put(exact.getFullName(), exact);
					}
				}
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					if (containsIgnoreCase(cls.getFullName(), cls.getRawName(), cls.getName(), cmpQuery, ignoreCase)) {
						matches.add(classToJson(cls));
						if (includeSource) {
							sourceClasses.put(cls.getFullName(), cls);
						}
					}
				}
			}
			if ("all".equals(kind) || "method".equals(kind)) {
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					for (JavaMethod method : cls.getMethods()) {
						if (matches.size() >= limit) {
							break;
						}
						if (containsIgnoreCase(method.getFullName(), method.getName(),
								method.getMethodNode().getMethodInfo().getShortId(), cmpQuery, ignoreCase)) {
							matches.add(methodToJson(method));
							if (includeSource) {
								sourceClasses.put(cls.getFullName(), cls);
							}
						}
					}
				}
			}
			if ("all".equals(kind) || "field".equals(kind)) {
				for (JavaClass cls : session.allClasses()) {
					if (matches.size() >= limit) {
						break;
					}
					for (JavaField field : cls.getFields()) {
						if (matches.size() >= limit) {
							break;
						}
						if (containsIgnoreCase(field.getFullName(), field.getName(), field.getRawName(), cmpQuery, ignoreCase)) {
							matches.add(fieldToJson(field));
							if (includeSource) {
								sourceClasses.put(cls.getFullName(), cls);
							}
						}
					}
				}
			}

			JsonObject out = new JsonObject();
			out.add("matches", matches);
			out.addProperty("count", matches.size());
			if (includeSource) {
				JsonObject sources = new JsonObject();
				for (Map.Entry<String, JavaClass> entry : sourceClasses.entrySet()) {
					sources.addProperty(entry.getKey(), entry.getValue().getCode());
				}
				out.add("sources", sources);
			}
			return out;
		}
	}

	private JsonObject toolGetAndroidManifest(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		synchronized (session) {
			ResourceFile manifest = findManifestResource(session);
			ResContainer container = manifest.loadContent();
			JsonObject out = new JsonObject();
			out.add("resource", resourceToJson(manifest));
			out.addProperty("content", extractResText(container));
			return out;
		}
	}

	private JsonObject toolGetMainActivity(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		boolean includeSource = getAsBoolean(argsObj, "include_source", true);
		synchronized (session) {
			String manifestXml = extractResText(findManifestResource(session).loadContent());
			String packageName = parsePackageName(manifestXml);
			String activityName = parseMainActivityName(manifestXml, packageName);
			if (activityName == null) {
				throw new IllegalArgumentException("No launcher activity found in AndroidManifest.xml");
			}
			JsonObject out = new JsonObject();
			out.addProperty("package_name", packageName);
			out.addProperty("activity_class", activityName);
			if (includeSource) {
				JavaClass cls = session.resolveClass(activityName, "auto");
				if (cls != null) {
					out.add("class", classToJson(cls));
					out.addProperty("source", cls.getCode());
				} else {
					out.addProperty("resolve_error", "Class not found in decompiled output: " + activityName);
				}
			}
			return out;
		}
	}

	private JsonObject toolGetAppClasses(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String packageName = getAsString(argsObj, "package_name", "");
		boolean includeInners = getAsBoolean(argsObj, "include_inners", false);
		boolean includeSource = getAsBoolean(argsObj, "include_source", false);
		int limit = getAsInt(argsObj, "limit", DEFAULT_LIMIT);
		int offset = getAsInt(argsObj, "offset", 0);
		synchronized (session) {
			if (packageName.isEmpty()) {
				String manifestXml = extractResText(findManifestResource(session).loadContent());
				packageName = parsePackageName(manifestXml);
				if (packageName == null || packageName.isEmpty()) {
					throw new IllegalArgumentException("Could not detect package name from AndroidManifest.xml — pass package_name explicitly");
				}
			}
			List<JavaClass> allClasses = session.listClasses(includeInners);
			JsonArray list = new JsonArray();
			JsonObject sources = new JsonObject();
			int matchIndex = 0;
			for (JavaClass cls : allClasses) {
				if (!cls.getPackage().startsWith(packageName)) {
					continue;
				}
				if (isKnownLibraryPackage(cls.getPackage())) {
					continue;
				}
				if (matchIndex >= offset && list.size() < limit) {
					list.add(classToJson(cls));
					if (includeSource) {
						sources.addProperty(cls.getFullName(), cls.getCode());
					}
				}
				matchIndex++;
			}
			JsonObject out = new JsonObject();
			out.addProperty("package_name", packageName);
			out.add("classes", list);
			out.addProperty("count", list.size());
			out.addProperty("total", matchIndex);
			out.addProperty("offset", offset);
			out.addProperty("has_more", offset + list.size() < matchIndex);
			if (includeSource) {
				out.add("sources", sources);
			}
			return out;
		}
	}

	private JsonObject toolGetStringsResource(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		synchronized (session) {
			ResourceFile stringsRes = null;
			for (ResourceFile resource : session.getDecompiler().getResources()) {
				String name = resource.getOriginalName();
				String deobf = resource.getDeobfName();
				if ("res/values/strings.xml".equals(name) || "res/values/strings.xml".equals(deobf)
						|| name.endsWith("/strings.xml") && name.contains("values/")) {
					stringsRes = resource;
					break;
				}
			}
			if (stringsRes == null) {
				for (ResourceFile resource : session.getDecompiler().getResources()) {
					if (resource.getOriginalName().endsWith("strings.xml")) {
						stringsRes = resource;
						break;
					}
				}
			}
			if (stringsRes == null) {
				throw new IllegalArgumentException("strings.xml not found in resources");
			}
			ResContainer container = stringsRes.loadContent();
			JsonObject out = new JsonObject();
			out.add("resource", resourceToJson(stringsRes));
			out.addProperty("content", extractResText(container));
			return out;
		}
	}

	private ResourceFile findManifestResource(DecompilerSession session) {
		for (ResourceFile resource : session.getDecompiler().getResources()) {
			String name = resource.getOriginalName();
			if ("AndroidManifest.xml".equals(name) || name.endsWith("/AndroidManifest.xml")) {
				return resource;
			}
		}
		for (ResourceFile resource : session.getDecompiler().getResources()) {
			String deobf = resource.getDeobfName();
			if (deobf != null && ("AndroidManifest.xml".equals(deobf) || deobf.endsWith("/AndroidManifest.xml"))) {
				return resource;
			}
		}
		throw new IllegalArgumentException("AndroidManifest.xml not found in resources — this may not be an APK/AAB file");
	}

	private String parsePackageName(String manifestXml) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(new StringReader(manifestXml)));
			return doc.getDocumentElement().getAttribute("package");
		} catch (Exception e) {
			return null;
		}
	}

	private String parseMainActivityName(String manifestXml, String packageName) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new InputSource(new StringReader(manifestXml)));
			NodeList activities = doc.getElementsByTagName("activity");
			for (int i = 0; i < activities.getLength(); i++) {
				Element activity = (Element) activities.item(i);
				NodeList filters = activity.getElementsByTagName("intent-filter");
				for (int j = 0; j < filters.getLength(); j++) {
					Element filter = (Element) filters.item(j);
					boolean hasMain = false;
					boolean hasLauncher = false;
					NodeList actions = filter.getElementsByTagName("action");
					for (int k = 0; k < actions.getLength(); k++) {
						String actionName = ((Element) actions.item(k)).getAttribute("android:name");
						if ("android.intent.action.MAIN".equals(actionName)) {
							hasMain = true;
						}
					}
					NodeList categories = filter.getElementsByTagName("category");
					for (int k = 0; k < categories.getLength(); k++) {
						String catName = ((Element) categories.item(k)).getAttribute("android:name");
						if ("android.intent.category.LAUNCHER".equals(catName)) {
							hasLauncher = true;
						}
					}
					if (hasMain && hasLauncher) {
						String name = activity.getAttribute("android:name");
						return resolveComponentName(name, packageName);
					}
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static String resolveComponentName(String name, String packageName) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		if (name.startsWith(".")) {
			return packageName + name;
		}
		if (name.contains(".")) {
			return name;
		}
		return packageName + "." + name;
	}

	private static boolean isKnownLibraryPackage(String pkg) {
		if (pkg == null) {
			return false;
		}
		return pkg.startsWith("android.")
				|| pkg.startsWith("androidx.")
				|| pkg.startsWith("com.google.")
				|| pkg.startsWith("kotlin.")
				|| pkg.startsWith("kotlinx.")
				|| pkg.startsWith("java.")
				|| pkg.startsWith("javax.")
				|| pkg.startsWith("org.apache.")
				|| pkg.startsWith("com.squareup.")
				|| pkg.startsWith("io.reactivex.")
				|| pkg.startsWith("rx.")
				|| pkg.startsWith("okhttp3.")
				|| pkg.startsWith("retrofit2.")
				|| pkg.startsWith("com.facebook.")
				|| pkg.startsWith("com.amazonaws.")
				|| pkg.startsWith("com.jakewharton.");
	}

	private JsonObject toolListResources(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String typeFilter = getAsString(argsObj, "type_filter", "");
		synchronized (session) {
			JsonArray resources = new JsonArray();
			for (ResourceFile resource : session.getDecompiler().getResources()) {
				if (!typeFilter.isEmpty() && !resource.getType().name().equalsIgnoreCase(typeFilter)) {
					continue;
				}
				resources.add(resourceToJson(resource));
			}
			JsonObject out = new JsonObject();
			out.add("resources", resources);
			out.addProperty("count", resources.size());
			return out;
		}
	}

	private JsonObject toolGetResourceContent(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String resourceName = requireString(argsObj, "resource_name");
		String mode = getAsString(argsObj, "mode", "text");
		String subFileName = getAsString(argsObj, "sub_file_name", "");
		synchronized (session) {
			ResourceFile resource = session.resolveResource(resourceName);
			if (resource == null) {
				throw new IllegalArgumentException("Resource not found: " + resourceName);
			}
			JsonObject out = new JsonObject();
			out.add("resource", resourceToJson(resource));
			if ("raw".equalsIgnoreCase(mode)) {
				byte[] bytes = decodeResource(resource);
				out.addProperty("encoding", "base64");
				out.addProperty("content", Base64.getEncoder().encodeToString(bytes));
				out.addProperty("size", bytes.length);
				return out;
			}
			ResContainer container = resource.loadContent();
			appendResContainer(out, container, subFileName);
			return out;
		}
	}

	private JsonObject toolGetErrorsReport(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		synchronized (session) {
			ErrorsCounter counter = session.getDecompiler().getRoot().getErrorsCounter();
			JsonArray errorNodes = new JsonArray();
			counter.getErrorNodes().forEach(node -> errorNodes.add(node.toString()));
			JsonArray warnNodes = new JsonArray();
			counter.getWarnNodes().forEach(node -> warnNodes.add(node.toString()));
			JsonObject out = new JsonObject();
			out.addProperty("errors_count", counter.getErrorCount());
			out.addProperty("warns_count", counter.getWarnsCount());
			out.add("error_nodes", errorNodes);
			out.add("warn_nodes", warnNodes);
			return out;
		}
	}

	private JsonObject toolExportProject(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String outDir = requireString(argsObj, "out_dir");
		boolean saveSources = getAsBoolean(argsObj, "save_sources", true);
		boolean saveResources = getAsBoolean(argsObj, "save_resources", true);
		synchronized (session) {
			JadxDecompiler decompiler = session.getDecompiler();
			JadxArgs args = decompiler.getArgs();
			File prevOut = args.getOutDir();
			File prevOutSrc = args.getOutDirSrc();
			File prevOutRes = args.getOutDirRes();
			boolean prevSkipSources = args.isSkipSources();
			boolean prevSkipResources = args.isSkipResources();
			try {
				File out = new File(outDir);
				args.setRootDir(out);
				args.setSkipSources(!saveSources);
				args.setSkipResources(!saveResources);
				decompiler.save();
			} finally {
				args.setOutDir(prevOut);
				args.setOutDirSrc(prevOutSrc);
				args.setOutDirRes(prevOutRes);
				args.setSkipSources(prevSkipSources);
				args.setSkipResources(prevSkipResources);
			}
			JsonObject out = new JsonObject();
			out.addProperty("out_dir", outDir);
			out.addProperty("save_sources", saveSources);
			out.addProperty("save_resources", saveResources);
			return out;
		}
	}

	private JsonObject toolListPlugins(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		synchronized (session) {
			JsonArray plugins = new JsonArray();
			for (PluginContext context : session.getDecompiler().getPluginManager().getResolvedPluginContexts()) {
				JsonObject p = new JsonObject();
				p.addProperty("plugin_id", context.getPluginId());
				p.addProperty("name", context.getPluginInfo().getName());
				p.addProperty("description", context.getPluginInfo().getDescription());
				p.addProperty("provides", context.getPluginInfo().getProvides());
				if (context.getPluginInfo().getRequiredJadxVersion() != null) {
					p.addProperty("required_jadx_version", context.getPluginInfo().getRequiredJadxVersion());
				}
				plugins.add(p);
			}
			JsonObject out = new JsonObject();
			out.add("plugins", plugins);
			out.addProperty("count", plugins.size());
			return out;
		}
	}

	private JsonObject toolListPluginOptions(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String pluginId = getAsString(argsObj, "plugin_id", "");
		synchronized (session) {
			JsonArray pluginOptions = new JsonArray();
			for (PluginContext context : session.getDecompiler().getPluginManager().getResolvedPluginContexts()) {
				if (!pluginId.isEmpty() && !context.getPluginId().equals(pluginId)) {
					continue;
				}
				JsonObject plugin = new JsonObject();
				plugin.addProperty("plugin_id", context.getPluginId());
				JsonArray options = new JsonArray();
				JadxPluginOptions opts = context.getOptions();
				if (opts != null) {
					for (OptionDescription desc : opts.getOptionsDescriptions()) {
						JsonObject o = new JsonObject();
						o.addProperty("name", desc.name());
						o.addProperty("description", desc.description());
						o.addProperty("type", desc.getType().name());
						if (desc.defaultValue() != null) {
							o.addProperty("default_value", desc.defaultValue());
						}
						JsonArray values = new JsonArray();
						for (String v : desc.values()) {
							values.add(v);
						}
						o.add("values", values);
						options.add(o);
					}
				}
				plugin.add("options", options);
				pluginOptions.add(plugin);
			}
			JsonObject out = new JsonObject();
			out.add("plugin_options", pluginOptions);
			out.addProperty("count", pluginOptions.size());
			return out;
		}
	}

	private JsonObject toolSetPluginOption(JsonObject argsObj) {
		DecompilerSession session = getSession(argsObj);
		String optionName = requireString(argsObj, "name");
		String optionValue = requireString(argsObj, "value");
		boolean reloadPasses = getAsBoolean(argsObj, "reload_passes", true);
		synchronized (session) {
			session.getDecompiler().getArgs().getPluginOptions().put(optionName, optionValue);
			if (reloadPasses) {
				session.getDecompiler().reloadPasses();
			}
			session.rebuildIndexes();
			JsonObject out = new JsonObject();
			out.addProperty("name", optionName);
			out.addProperty("value", optionValue);
			out.addProperty("reload_passes", reloadPasses);
			return out;
		}
	}

	private JsonObject toolPluginsInstall(JsonObject argsObj) {
		String locationId = requireString(argsObj, "location_id");
		JadxPluginMetadata plugin = JadxPluginsTools.getInstance().install(locationId);
		JsonObject out = new JsonObject();
		out.addProperty("location_id", locationId);
		out.addProperty("plugin_id", plugin.getPluginId());
		out.addProperty("name", plugin.getName());
		if (plugin.getVersion() != null) {
			out.addProperty("version", plugin.getVersion());
		}
		return out;
	}

	private JsonObject toolPluginsUpdateAll() {
		List<JadxPluginUpdate> updates = JadxPluginsTools.getInstance().updateAll();
		JsonArray list = new JsonArray();
		for (JadxPluginUpdate update : updates) {
			JsonObject item = new JsonObject();
			item.addProperty("plugin_id", update.getPluginId());
			item.addProperty("old_version", update.getOldVersion());
			item.addProperty("new_version", update.getNewVersion());
			list.add(item);
		}
		JsonObject out = new JsonObject();
		out.add("updates", list);
		out.addProperty("count", list.size());
		return out;
	}

	private JsonObject toolPluginsUninstall(JsonObject argsObj) {
		String pluginId = requireString(argsObj, "plugin_id");
		boolean removed = JadxPluginsTools.getInstance().uninstall(pluginId);
		JsonObject out = new JsonObject();
		out.addProperty("plugin_id", pluginId);
		out.addProperty("removed", removed);
		return out;
	}

	private void appendResContainer(JsonObject out, ResContainer container, String subFileName) {
		out.addProperty("data_type", container.getDataType().name());
		out.addProperty("name", container.getName());
		JsonArray subFiles = new JsonArray();
		for (ResContainer sub : container.getSubFiles()) {
			subFiles.add(sub.getName());
		}
		out.add("sub_files", subFiles);
		if (!subFileName.isEmpty()) {
			ResContainer selected = null;
			for (ResContainer sub : container.getSubFiles()) {
				if (subFileName.equals(sub.getName())) {
					selected = sub;
					break;
				}
			}
			if (selected == null) {
				throw new IllegalArgumentException("Sub file not found in resource container: " + subFileName);
			}
			out.addProperty("selected_sub_file", selected.getName());
			out.addProperty("content", extractResText(selected));
		} else {
			out.addProperty("content", extractResText(container));
		}
	}

	private static String extractResText(ResContainer container) {
		switch (container.getDataType()) {
			case TEXT:
			case RES_TABLE:
				ICodeInfo text = container.getText();
				return text == null ? "" : text.getCodeStr();
			case DECODED_DATA:
				return new String(container.getDecodedData(), StandardCharsets.UTF_8);
			case RES_LINK:
			default:
				return "";
		}
	}

	private byte[] decodeResource(ResourceFile resource) {
		try {
			return jadx.api.ResourcesLoader.decodeStream(resource, (size, in) -> in.readAllBytes());
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to decode resource bytes: " + resource.getOriginalName(), e);
		}
	}

	private static JsonObject codeMatchToJson(JavaClass cls, String code, int start, int end) {
		int lineStartPos = code.lastIndexOf('\n', start);
		int lineEndPos = code.indexOf('\n', end);
		int lineStart = lineStartPos == -1 ? 0 : lineStartPos + 1;
		int lineEnd = lineEndPos == -1 ? code.length() : lineEndPos;
		String line = code.substring(lineStart, lineEnd).trim();
		JsonObject result = new JsonObject();
		result.add("class", classToJson(cls));
		result.addProperty("start", start);
		result.addProperty("end", end);
		result.addProperty("line", line);
		return result;
	}

	private DecompilerSession getSession(JsonObject argsObj) {
		String sessionId = requireString(argsObj, "session_id");
		return sessionManager.get(sessionId);
	}

	private static JavaClass requireClass(DecompilerSession session, String className, String naming) {
		JavaClass cls = session.resolveClass(className, naming);
		if (cls == null) {
			throw new IllegalArgumentException("Class not found: " + className + " (naming=" + naming + ')');
		}
		return cls;
	}

	private JavaNode resolveNode(DecompilerSession session, JsonObject symbol) {
		String type = requireString(symbol, "type").toLowerCase(Locale.ROOT);
		switch (type) {
			case "class":
				return requireClass(session, requireString(symbol, "class_name"), getAsString(symbol, "naming", "auto"));
			case "method": {
				JavaClass cls = requireClass(session, requireString(symbol, "class_name"), getAsString(symbol, "naming", "auto"));
				String shortId = requireString(symbol, "method_short_id");
				JavaMethod method = cls.searchMethodByShortId(shortId);
				if (method == null) {
					throw new IllegalArgumentException("Method not found by short id: " + shortId);
				}
				return method;
			}
			case "field": {
				JavaClass cls = requireClass(session, requireString(symbol, "class_name"), getAsString(symbol, "naming", "auto"));
				String fieldName = requireString(symbol, "field_name");
				for (JavaField field : cls.getFields()) {
					if (field.getName().equals(fieldName)
							|| field.getRawName().equals(fieldName)
							|| field.getFullName().equals(fieldName)
							|| field.getFieldNode().getFieldInfo().getShortId().equals(fieldName)) {
						return field;
					}
				}
				throw new IllegalArgumentException("Field not found: " + fieldName);
			}
			default:
				throw new IllegalArgumentException("Unsupported symbol type: " + type);
		}
	}

	private Set<String> getKinds(JsonObject argsObj) {
		JsonArray kindsArg = argsObj.getAsJsonArray("kinds");
		if (kindsArg == null || kindsArg.isEmpty()) {
			return new HashSet<>(List.of("class", "method", "field", "package"));
		}
		Set<String> kinds = new HashSet<>();
		for (JsonElement element : kindsArg) {
			kinds.add(element.getAsString().toLowerCase(Locale.ROOT));
		}
		return kinds;
	}

	private static boolean matchesClass(Pattern pattern, JavaClass cls) {
		return pattern.matcher(cls.getName()).find()
				|| pattern.matcher(cls.getFullName()).find()
				|| pattern.matcher(cls.getRawName()).find();
	}

	private static boolean matchesPattern(Pattern pattern, String first, String second, String third,
			String query, boolean ignoreCase) {
		if (pattern != null) {
			return pattern.matcher(first).find() || pattern.matcher(second).find() || pattern.matcher(third).find();
		}
		String q = ignoreCase ? query.toLowerCase(Locale.ROOT) : query;
		return containsString(first, q, ignoreCase)
				|| containsString(second, q, ignoreCase)
				|| containsString(third, q, ignoreCase);
	}

	private static boolean containsIgnoreCase(String a, String b, String c, String cmpQuery, boolean ignoreCase) {
		return containsString(a, cmpQuery, ignoreCase)
				|| containsString(b, cmpQuery, ignoreCase)
				|| containsString(c, cmpQuery, ignoreCase);
	}

	private static boolean containsString(String src, String needle, boolean ignoreCase) {
		if (src == null) {
			return false;
		}
		if (ignoreCase) {
			return src.toLowerCase(Locale.ROOT).contains(needle);
		}
		return src.contains(needle);
	}

	private static Pattern buildPattern(String query, boolean regex, boolean ignoreCase) {
		if (query == null || query.isEmpty()) {
			return null;
		}
		if (!regex) {
			return Pattern.compile(Pattern.quote(query), ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
		}
		return Pattern.compile(query, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
	}

	private JsonArray buildToolsSchema() {
		List<JsonObject> tools = new ArrayList<>();
		tools.add(tool("session_open", "Open jadx decompilation session",
				obj("type", "object", "required", arr("input_files"), "properties", obj(
						"input_files", obj("type", "array", "items", obj("type", "string")),
						"options", obj("type", "object"),
						"plugin_options", obj("type", "object")))));
		tools.add(tool("session_close", "Close jadx session",
				obj("type", "object", "required", arr("session_id"), "properties", obj("session_id", obj("type", "string")))));
		tools.add(tool("session_info", "Get session details",
				obj("type", "object", "required", arr("session_id"), "properties", obj("session_id", obj("type", "string")))));
		tools.add(tool("list_classes", "List classes in session with optional filtering. Supports pagination via offset+limit. Returns total matching count and has_more flag.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"package_prefix", obj("type", "string"),
						"name_query", obj("type", "string"),
						"regex", obj("type", "boolean"),
						"ignore_case", obj("type", "boolean"),
						"include_inners", obj("type", "boolean"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer", "description", "Number of matching results to skip for pagination (default 0)")))));
		tools.add(tool("get_class_source", "Get decompiled Java source for one or more classes. Pass class_name for a single class or class_names array for batch fetch.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"class_name", obj("type", "string", "description", "Single class full name (use this or class_names)"),
						"class_names", obj("type", "array", "items", obj("type", "string"), "description", "List of class full names for batch fetch"),
						"naming", obj("type", "string")))));
		tools.add(tool("get_class_smali", "Get disassembled smali for class",
				obj("type", "object", "required", arr("session_id", "class_name"), "properties", obj(
						"session_id", obj("type", "string"),
						"class_name", obj("type", "string"),
						"naming", obj("type", "string")))));
		tools.add(tool("get_method_source", "Get method source snippet",
				obj("type", "object", "required", arr("session_id", "class_name", "method_short_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"class_name", obj("type", "string"),
						"method_short_id", obj("type", "string"),
						"naming", obj("type", "string")))));
		tools.add(tool("resolve_symbol", "Resolve class/method/field symbols. Supports pagination via offset+limit.",
				obj("type", "object", "required", arr("session_id", "query"), "properties", obj(
						"session_id", obj("type", "string"),
						"query", obj("type", "string"),
						"kind", obj("type", "string"),
						"ignore_case", obj("type", "boolean"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer", "description", "Number of matches to skip for pagination (default 0)")))));
		tools.add(tool("find_usages", "Find incoming usages for symbol. Supports pagination via offset+limit. Returns total usage count.",
				obj("type", "object", "required", arr("session_id", "symbol"), "properties", obj(
						"session_id", obj("type", "string"),
						"symbol", obj("type", "object"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer", "description", "Number of usages to skip for pagination (default 0)")))));
		tools.add(tool("find_method_calls", "Find incoming/outgoing method calls",
				obj("type", "object", "required", arr("session_id", "class_name", "method_short_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"class_name", obj("type", "string"),
						"method_short_id", obj("type", "string"),
						"naming", obj("type", "string")))));
		tools.add(tool("search_code", "Search decompiled source text. Set include_source=true to get full class sources for all matching classes in one call. Supports pagination via offset+limit with has_more flag.",
				obj("type", "object", "required", arr("session_id", "query"), "properties", obj(
						"session_id", obj("type", "string"),
						"query", obj("type", "string"),
						"regex", obj("type", "boolean"),
						"ignore_case", obj("type", "boolean"),
						"package_prefix", obj("type", "string"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer", "description", "Number of matches to skip for pagination (default 0)"),
						"include_source", obj("type", "boolean", "description", "Include full decompiled source for each matching class, keyed by full class name")))));
		tools.add(tool("search_symbols", "Search class/method/field symbols. Set include_source=true to include full class source for each match. Supports pagination via offset+limit.",
				obj("type", "object", "required", arr("session_id", "query"), "properties", obj(
						"session_id", obj("type", "string"),
						"query", obj("type", "string"),
						"kinds", obj("type", "array", "items", obj("type", "string")),
						"regex", obj("type", "boolean"),
						"ignore_case", obj("type", "boolean"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer", "description", "Number of matches to skip for pagination (default 0)"),
						"include_source", obj("type", "boolean", "description", "Include full decompiled source for each matched class, keyed by full class name")))));
		tools.add(tool("list_resources", "List resources in session",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"type_filter", obj("type", "string")))));
		tools.add(tool("get_resource_content", "Get resource decoded content",
				obj("type", "object", "required", arr("session_id", "resource_name"), "properties", obj(
						"session_id", obj("type", "string"),
						"resource_name", obj("type", "string"),
						"mode", obj("type", "string"),
						"sub_file_name", obj("type", "string")))));
		tools.add(tool("get_errors_report", "Get decompilation errors/warnings",
				obj("type", "object", "required", arr("session_id"), "properties", obj("session_id", obj("type", "string")))));
		tools.add(tool("export_project", "Export sources/resources to output directory",
				obj("type", "object", "required", arr("session_id", "out_dir"), "properties", obj(
						"session_id", obj("type", "string"),
						"out_dir", obj("type", "string"),
						"save_sources", obj("type", "boolean"),
						"save_resources", obj("type", "boolean")))));
		tools.add(tool("list_plugins", "List resolved jadx plugins",
				obj("type", "object", "required", arr("session_id"), "properties", obj("session_id", obj("type", "string")))));
		tools.add(tool("list_plugin_options", "List plugin options and defaults",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"plugin_id", obj("type", "string")))));
		tools.add(tool("set_plugin_option", "Set plugin option and reload passes",
				obj("type", "object", "required", arr("session_id", "name", "value"), "properties", obj(
						"session_id", obj("type", "string"),
						"name", obj("type", "string"),
						"value", obj("type", "string"),
						"reload_passes", obj("type", "boolean")))));
		tools.add(tool("plugins_install", "Install external jadx plugin",
				obj("type", "object", "required", arr("location_id"), "properties", obj(
						"location_id", obj("type", "string")))));
		tools.add(tool("plugins_update_all", "Update installed external plugins",
				obj("type", "object", "properties", obj())));
		tools.add(tool("plugins_uninstall", "Uninstall external jadx plugin",
				obj("type", "object", "required", arr("plugin_id"), "properties", obj(
						"plugin_id", obj("type", "string")))));
		tools.add(tool("get_android_manifest", "Retrieve the decoded AndroidManifest.xml from the loaded APK/AAB. Returns the full manifest XML content.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string")))));
		tools.add(tool("get_main_activity", "Find the launcher Activity from AndroidManifest.xml and return its full class name and decompiled source in one call.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"include_source", obj("type", "boolean", "description", "Include decompiled Java source of the main activity class (default: true)")))));
		tools.add(tool("get_app_classes", "List all classes belonging to the app's own package (auto-detected from AndroidManifest.xml), filtering out known library packages. Optionally include full source.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string"),
						"package_name", obj("type", "string", "description", "Override the app package name instead of auto-detecting from manifest"),
						"include_inners", obj("type", "boolean"),
						"include_source", obj("type", "boolean", "description", "Include full decompiled source for each class (default: false)"),
						"limit", obj("type", "integer"),
						"offset", obj("type", "integer")))));
		tools.add(tool("get_strings_resource", "Retrieve res/values/strings.xml content from the loaded APK. Useful for finding hardcoded strings, API endpoints, and keys.",
				obj("type", "object", "required", arr("session_id"), "properties", obj(
						"session_id", obj("type", "string")))));
		tools.add(tool("get_class_deep", "Get full decompiled source, fields, methods, usages, and inner classes for a class in a single call. Avoids the need for separate get_class_source + find_usages + find_method_calls calls.",
				obj("type", "object", "required", arr("session_id", "class_name"), "properties", obj(
						"session_id", obj("type", "string"),
						"class_name", obj("type", "string"),
						"naming", obj("type", "string"),
						"include_usages", obj("type", "boolean", "description", "Include all usages of this class (default: true)"),
						"include_method_calls", obj("type", "boolean", "description", "Include calls and called_from for every method (default: false)"),
						"include_inners_source", obj("type", "boolean", "description", "Include full source for each inner class (default: false, returns metadata only)")))));
		tools.add(tool("search_and_resolve", "Search for symbols by name and immediately return their full decompiled class sources. Combines resolve_symbol + get_class_source in one call.",
				obj("type", "object", "required", arr("session_id", "query"), "properties", obj(
						"session_id", obj("type", "string"),
						"query", obj("type", "string"),
						"kind", obj("type", "string", "description", "Filter to: all, class, method, field (default: all)"),
						"limit", obj("type", "integer", "description", "Max number of symbol matches to return (default: 10)"),
						"ignore_case", obj("type", "boolean"),
						"include_source", obj("type", "boolean", "description", "Include full source for each matched class (default: true)")))));
		tools.sort(Comparator.comparing(t -> t.get("name").getAsString()));
		JsonArray arr = new JsonArray();
		tools.forEach(arr::add);
		return arr;
	}

	private static JsonObject tool(String name, String description, JsonObject inputSchema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", inputSchema);
		return tool;
	}

	private static JsonObject toolSuccess(JsonObject payload) {
		JsonObject result = new JsonObject();
		result.addProperty("isError", false);
		result.add("structuredContent", payload);
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", payload.toString());
		content.add(text);
		result.add("content", content);
		return result;
	}

	private static JsonObject toolError(String message) {
		JsonObject result = new JsonObject();
		result.addProperty("isError", true);
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", message);
		content.add(text);
		result.add("content", content);
		return result;
	}

	private JsonObject buildInitializeResult(JsonObject params) {
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", getAsString(params, "protocolVersion", PROTOCOL_VERSION));
		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		result.add("capabilities", capabilities);
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "jadx-mcp");
		serverInfo.addProperty("version", JadxDecompiler.getVersion());
		result.add("serverInfo", serverInfo);
		return result;
	}

	private JsonObject success(JsonElement id, JsonObject resultData) {
		if (id == null || id.isJsonNull()) {
			return null;
		}
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		resp.add("result", resultData);
		return resp;
	}

	private JsonObject error(JsonElement id, int code, String message) {
		if (id == null || id.isJsonNull()) {
			return null;
		}
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		resp.add("id", id);
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		resp.add("error", err);
		return resp;
	}

	private static JsonObject classToJson(JavaClass cls) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "class");
		json.addProperty("name", cls.getName());
		json.addProperty("full_name", cls.getFullName());
		json.addProperty("raw_name", cls.getRawName());
		json.addProperty("package", cls.getPackage());
		json.addProperty("is_inner", cls.isInner());
		json.addProperty("is_no_code", cls.isNoCode());
		return json;
	}

	private static JsonObject methodToJson(JavaMethod method) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "method");
		json.addProperty("name", method.getName());
		json.addProperty("full_name", method.getFullName());
		json.addProperty("short_id", method.getMethodNode().getMethodInfo().getShortId());
		json.add("declaring_class", classToJson(method.getDeclaringClass()));
		json.addProperty("is_constructor", method.isConstructor());
		json.addProperty("is_class_init", method.isClassInit());
		return json;
	}

	private static JsonObject fieldToJson(JavaField field) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "field");
		json.addProperty("name", field.getName());
		json.addProperty("raw_name", field.getRawName());
		json.addProperty("full_name", field.getFullName());
		json.addProperty("short_id", field.getFieldNode().getFieldInfo().getShortId());
		json.add("declaring_class", classToJson(field.getDeclaringClass()));
		return json;
	}

	private static JsonObject packageToJson(JavaPackage pkg) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "package");
		json.addProperty("name", pkg.getName());
		json.addProperty("full_name", pkg.getFullName());
		json.addProperty("raw_full_name", pkg.getRawFullName());
		return json;
	}

	private static JsonObject nodeToJson(JavaNode node) {
		if (node instanceof JavaClass) {
			return classToJson((JavaClass) node);
		}
		if (node instanceof JavaMethod) {
			return methodToJson((JavaMethod) node);
		}
		if (node instanceof JavaField) {
			return fieldToJson((JavaField) node);
		}
		if (node instanceof JavaPackage) {
			return packageToJson((JavaPackage) node);
		}
		JsonObject json = new JsonObject();
		json.addProperty("type", "unknown");
		json.addProperty("name", node.getName());
		json.addProperty("full_name", node.getFullName());
		if (node.getDeclaringClass() != null) {
			json.add("declaring_class", classToJson(node.getDeclaringClass()));
		}
		return json;
	}

	private static JsonObject resourceToJson(ResourceFile resource) {
		JsonObject json = new JsonObject();
		json.addProperty("original_name", resource.getOriginalName());
		json.addProperty("deobf_name", resource.getDeobfName());
		json.addProperty("type", resource.getType().name());
		if (resource.getZipEntry() != null) {
			json.addProperty("zip_entry", resource.getZipEntry().getName());
		}
		return json;
	}

	private static JsonObject getAsObject(JsonObject obj, String key) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return null;
		}
		JsonElement element = obj.get(key);
		if (element.isJsonObject()) {
			return element.getAsJsonObject();
		}
		return null;
	}

	private static String getAsString(JsonObject obj, String key, String defaultValue) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return defaultValue;
		}
		return obj.get(key).getAsString();
	}

	private static int getAsInt(JsonObject obj, String key, int defaultValue) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return defaultValue;
		}
		return obj.get(key).getAsInt();
	}

	private static boolean getAsBoolean(JsonObject obj, String key, boolean defaultValue) {
		if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
			return defaultValue;
		}
		return obj.get(key).getAsBoolean();
	}

	private static String requireString(JsonObject obj, String key) {
		String value = getAsString(obj, key, "");
		if (value.isEmpty()) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return value;
	}

	private static JsonArray arr(String... items) {
		JsonArray arr = new JsonArray();
		for (String item : items) {
			arr.add(item);
		}
		return arr;
	}

	private static JsonObject obj(Object... pairs) {
		JsonObject json = new JsonObject();
		for (int i = 0; i < pairs.length; i += 2) {
			String key = (String) pairs[i];
			Object value = pairs[i + 1];
			json.add(key, toJsonValue(value));
		}
		return json;
	}

	private static JsonElement toJsonValue(Object value) {
		if (value == null) {
			return JsonNull.INSTANCE;
		}
		if (value instanceof JsonElement) {
			return (JsonElement) value;
		}
		if (value instanceof String) {
			return new JsonPrimitive((String) value);
		}
		if (value instanceof Boolean) {
			return new JsonPrimitive((Boolean) value);
		}
		if (value instanceof Number) {
			return new JsonPrimitive((Number) value);
		}
		throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
	}
}
