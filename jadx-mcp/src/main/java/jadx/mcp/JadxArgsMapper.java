package jadx.mcp;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.CommentsLevel;
import jadx.api.DecompilationMode;
import jadx.api.JadxArgs;
import jadx.api.JadxArgs.OutputFormatEnum;
import jadx.api.JadxArgs.RenameEnum;
import jadx.api.args.IntegerFormat;
import jadx.api.args.ResourceNameSource;
import jadx.api.args.UseSourceNameAsClassNameAlias;
import jadx.api.security.IJadxSecurity;
import jadx.api.security.JadxSecurityFlag;
import jadx.api.security.impl.JadxSecurity;
import jadx.plugins.tools.JadxExternalPluginsLoader;

public final class JadxArgsMapper {

	private JadxArgsMapper() {
	}

	public static JadxArgs buildArgs(JsonObject argsObj, JsonObject pluginOptionsObj) {
		JadxArgs args = new JadxArgs();
		args.setPluginLoader(new JadxExternalPluginsLoader());
		List<File> inputFiles = parseInputFiles(argsObj);
		args.setInputFiles(inputFiles);
		if (argsObj != null) {
			applyOptions(args, argsObj);
		}
		if (pluginOptionsObj != null) {
			Map<String, String> pluginOptions = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : pluginOptionsObj.entrySet()) {
				pluginOptions.put(entry.getKey(), entry.getValue().getAsString());
			}
			args.setPluginOptions(pluginOptions);
		}
		return args;
	}

	private static List<File> parseInputFiles(JsonObject argsObj) {
		if (argsObj == null || !argsObj.has("input_files")) {
			throw new IllegalArgumentException("Missing required argument: input_files");
		}
		JsonArray arr = argsObj.getAsJsonArray("input_files");
		if (arr.isEmpty()) {
			throw new IllegalArgumentException("input_files must not be empty");
		}
		List<File> files = new ArrayList<>(arr.size());
		for (JsonElement element : arr) {
			files.add(Path.of(element.getAsString()).toFile());
		}
		return files;
	}

	private static void applyOptions(JadxArgs args, JsonObject options) {
		if (options.has("threads_count")) {
			args.setThreadsCount(options.get("threads_count").getAsInt());
		}
		if (options.has("skip_sources")) {
			args.setSkipSources(options.get("skip_sources").getAsBoolean());
		}
		if (options.has("skip_resources")) {
			args.setSkipResources(options.get("skip_resources").getAsBoolean());
		}
		if (options.has("show_inconsistent_code")) {
			args.setShowInconsistentCode(options.get("show_inconsistent_code").getAsBoolean());
		}
		if (options.has("decompilation_mode")) {
			args.setDecompilationMode(DecompilationMode.valueOf(options.get("decompilation_mode").getAsString().toUpperCase()));
		}
		if (options.has("output_format")) {
			args.setOutputFormat(OutputFormatEnum.valueOf(options.get("output_format").getAsString().toUpperCase()));
		}
		if (options.has("use_imports")) {
			args.setUseImports(options.get("use_imports").getAsBoolean());
		}
		if (options.has("debug_info")) {
			args.setDebugInfo(options.get("debug_info").getAsBoolean());
		}
		if (options.has("insert_debug_lines")) {
			args.setInsertDebugLines(options.get("insert_debug_lines").getAsBoolean());
		}
		if (options.has("inline_anonymous_classes")) {
			args.setInlineAnonymousClasses(options.get("inline_anonymous_classes").getAsBoolean());
		}
		if (options.has("inline_methods")) {
			args.setInlineMethods(options.get("inline_methods").getAsBoolean());
		}
		if (options.has("allow_inline_kotlin_lambda")) {
			args.setAllowInlineKotlinLambda(options.get("allow_inline_kotlin_lambda").getAsBoolean());
		}
		if (options.has("move_inner_classes")) {
			args.setMoveInnerClasses(options.get("move_inner_classes").getAsBoolean());
		}
		if (options.has("extract_finally")) {
			args.setExtractFinally(options.get("extract_finally").getAsBoolean());
		}
		if (options.has("deobfuscation_on")) {
			args.setDeobfuscationOn(options.get("deobfuscation_on").getAsBoolean());
		}
		if (options.has("deobfuscation_min_length")) {
			args.setDeobfuscationMinLength(options.get("deobfuscation_min_length").getAsInt());
		}
		if (options.has("deobfuscation_max_length")) {
			args.setDeobfuscationMaxLength(options.get("deobfuscation_max_length").getAsInt());
		}
		if (options.has("resource_name_source")) {
			args.setResourceNameSource(ResourceNameSource.valueOf(options.get("resource_name_source").getAsString().toUpperCase()));
		}
		if (options.has("use_source_name_as_class_name_alias")) {
			args.setUseSourceNameAsClassNameAlias(
					UseSourceNameAsClassNameAlias.valueOf(options.get("use_source_name_as_class_name_alias").getAsString().toUpperCase()));
		}
		if (options.has("source_name_repeat_limit")) {
			args.setSourceNameRepeatLimit(options.get("source_name_repeat_limit").getAsInt());
		}
		if (options.has("escape_unicode")) {
			args.setEscapeUnicode(options.get("escape_unicode").getAsBoolean());
		}
		if (options.has("replace_consts")) {
			args.setReplaceConsts(options.get("replace_consts").getAsBoolean());
		}
		if (options.has("respect_bytecode_access_modifiers")) {
			args.setRespectBytecodeAccModifiers(options.get("respect_bytecode_access_modifiers").getAsBoolean());
		}
		if (options.has("restore_switch_over_string")) {
			args.setRestoreSwitchOverString(options.get("restore_switch_over_string").getAsBoolean());
		}
		if (options.has("skip_xml_pretty_print")) {
			args.setSkipXmlPrettyPrint(options.get("skip_xml_pretty_print").getAsBoolean());
		}
		if (options.has("fs_case_sensitive")) {
			args.setFsCaseSensitive(options.get("fs_case_sensitive").getAsBoolean());
		}
		if (options.has("comments_level")) {
			args.setCommentsLevel(CommentsLevel.valueOf(options.get("comments_level").getAsString().toUpperCase()));
		}
		if (options.has("integer_format")) {
			args.setIntegerFormat(IntegerFormat.valueOf(options.get("integer_format").getAsString().toUpperCase()));
		}
		if (options.has("type_updates_limit_count")) {
			args.setTypeUpdatesLimitCount(options.get("type_updates_limit_count").getAsInt());
		}
		if (options.has("use_dx_input")) {
			args.setUseDxInput(options.get("use_dx_input").getAsBoolean());
		}
		if (options.has("use_headers_for_detect_resource_extensions")) {
			args.setUseHeadersForDetectResourceExtensions(options.get("use_headers_for_detect_resource_extensions").getAsBoolean());
		}
		if (options.has("include_dependencies")) {
			args.setIncludeDependencies(options.get("include_dependencies").getAsBoolean());
		}
		if (options.has("class_filter_prefix")) {
			String prefix = options.get("class_filter_prefix").getAsString();
			Predicate<String> classFilter = clsName -> clsName.startsWith(prefix);
			args.setClassFilter(classFilter);
		}
		if (options.has("rename_flags")) {
			Set<RenameEnum> flags = EnumSet.noneOf(RenameEnum.class);
			JsonArray arr = options.getAsJsonArray("rename_flags");
			for (JsonElement element : arr) {
				flags.add(RenameEnum.valueOf(element.getAsString().toUpperCase()));
			}
			args.setRenameFlags(flags);
		}
		if (options.has("security")) {
			JsonObject security = options.getAsJsonObject("security");
			args.setSecurity(buildSecurity(security));
		}
	}

	private static IJadxSecurity buildSecurity(JsonObject securityOptions) {
		Set<JadxSecurityFlag> flags = EnumSet.noneOf(JadxSecurityFlag.class);
		if (readBool(securityOptions, "verify_app_package", true)) {
			flags.add(JadxSecurityFlag.VERIFY_APP_PACKAGE);
		}
		if (readBool(securityOptions, "secure_xml_parser", true)) {
			flags.add(JadxSecurityFlag.SECURE_XML_PARSER);
		}
		if (readBool(securityOptions, "secure_zip_reader", true)) {
			flags.add(JadxSecurityFlag.SECURE_ZIP_READER);
		}
		return new JadxSecurity(flags);
	}

	private static boolean readBool(JsonObject obj, String key, boolean defaultValue) {
		if (!obj.has(key)) {
			return defaultValue;
		}
		return obj.get(key).getAsBoolean();
	}
}
