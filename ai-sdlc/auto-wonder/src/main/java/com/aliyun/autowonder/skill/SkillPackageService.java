package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.alibaba.fastjson.JSON;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class SkillPackageService {

    static final String SOURCE_TYPE_OSS_ZIP = "OSS_ZIP";
    private static final String SKILL_TYPE = "SKILL";
    private static final String HOOK_TYPE = "HOOK";
    private static final int MAX_ENTRIES = 500;
    static final long MAX_PACKAGE_SIZE = 100L * 1024L * 1024L;
    private static final Set<String> DIRECT_PLUGIN_PROVIDERS = Set.of("claude", "qoder");
    private static final Set<String> HOOK_TRIGGERS = Set.of(
            "beforeRepoPrepare", "afterRepoPrepare", "beforeAgentStart", "afterAgentExit",
            "beforeStep", "afterStep", "beforeTool", "afterTool",
            "beforeCommit", "beforePush", "onFailure", "cleanup");

    private final SkillDao skillDao;
    private final SkillService skillService;
    private final ObjectStorage storage;
    private final String bucket;

    @Autowired
    public SkillPackageService(SkillDao skillDao, SkillService skillService,
                               ObjectStorage storage, OssProperties ossProperties) {
        this(skillDao, skillService, storage, chooseBucket(ossProperties));
    }

    SkillPackageService(SkillDao skillDao, SkillService skillService,
                        ObjectStorage storage, String bucket) {
        this.skillDao = skillDao;
        this.skillService = skillService;
        this.storage = storage;
        this.bucket = bucket;
    }

    public SkillPackageInspectVO inspect(MultipartFile file) {
        ParsedPackage parsed = parse(file);
        return inspect(parsed);
    }

    public SkillPackageInspectVO inspect(String fileName, byte[] bytes) {
        return inspect(parse(fileName, bytes));
    }

    public UploadedPackage uploadMcpPackage(String fileName, byte[] bytes, String type, String name,
                                            String description, List<String> providers,
                                            String expectedMd5, long tenantId) {
        String normalizedType = normalizePackageType(type);
        ParsedPackage parsed = parseByType(normalizedType, fileName, bytes, name, description);
        verifyDigest(bytes, expectedMd5);
        String sha256 = digest(bytes, "SHA-256");
        StoredObject stored = storage.put(bucket,
                "t/" + tenantId + "/skills/packages/" + sha256 + "/" + parsed.fileName, bytes);
        return new UploadedPackage(stored.getOssRef(), parsed.fileName, stored.getSize(), stored.getMd5(),
                sha256, normalizedType, parsed.name, parsed.description);
    }

    @Transactional
    public SkillVO createFromUploadedPackage(String packageOssRef, String type, String name, String description,
                                             List<String> providers, String expectedMd5, String idempotencyKey,
                                             long tenantId, long userId) {
        PackageBytes packageBytes = loadUploadedPackage(packageOssRef, expectedMd5);
        return createFromPackageBytes(packageBytes.fileName, packageBytes.bytes, type, name, description, providers,
                tenantId, userId, idempotencyKey);
    }

    @Transactional
    public SkillVO updateUploadedPackage(long id, String packageOssRef, String name, String description,
                                         List<String> providers, String expectedMd5, String idempotencyKey,
                                         long tenantId, long userId) {
        PackageBytes packageBytes = loadUploadedPackage(packageOssRef, expectedMd5);
        return updatePackageBytes(id, packageBytes.fileName, packageBytes.bytes, name, description, providers,
                tenantId, userId, idempotencyKey);
    }

    @Transactional
    public SkillVO createFromPackage(MultipartFile file, String type, String name, String description,
                                     List<String> providers, long tenantId, long userId) {
        try {
            return createFromPackageBytes(file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getBytes(), type, name, description, providers,
                    tenantId, userId, null);
        } catch (IOException e) {
            throw invalid();
        }
    }

    SkillVO createFromPackage(MultipartFile file, long tenantId, long userId) {
        return createFromPackage(file, SKILL_TYPE, null, null, null, tenantId, userId);
    }

    @Transactional
    public SkillVO updatePackage(long id, MultipartFile file, String name, String description,
                                 List<String> providers, long tenantId, long userId) {
        try {
            return updatePackageBytes(id, file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getBytes(), name, description, providers, tenantId, userId, null);
        } catch (IOException e) {
            throw invalid();
        }
    }

    SkillVO updatePackage(long id, MultipartFile file, long tenantId, long userId) {
        return updatePackage(id, file, null, null, null, tenantId, userId);
    }

    private SkillVO createFromPackageBytes(String fileName, byte[] bytes, String type, String name, String description,
                                           List<String> providers, long tenantId, long userId,
                                           String idempotencyKey) {
        String normalizedType = normalizePackageType(type);
        ParsedPackage parsed = parseByType(normalizedType, fileName, bytes, name, description);
        SkillDO duplicate = skillDao.findByTypeAndName(tenantId, normalizedType, parsed.name);
        if (duplicate != null) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()
                    && duplicate.getPackageMd5() != null
                    && duplicate.getPackageMd5().equalsIgnoreCase(digest(bytes, "MD5"))) {
                return skillService.get(duplicate.getId());
            }
            throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
        }
        SkillDO skill = new SkillDO();
        skill.setTenantId(tenantId);
        skill.setType(normalizedType);
        skill.setName(parsed.name);
        skill.setDescription(parsed.description);
        skill.setSourceType(SOURCE_TYPE_OSS_ZIP);
        skill.setInstallSpec(installSpec(normalizedType, providers));
        skill.setCreatorId(userId);
        skill.setVersion(0);
        skillDao.insert(skill);
        StoredObject stored = putPackage(tenantId, skill.getId(), parsed.bytes);
        updatePackageRecord(skill.getId(), tenantId, normalizedType, providers, parsed, stored, 0, userId);
        return skillService.get(skill.getId());
    }

    private SkillVO updatePackageBytes(long id, String fileName, byte[] bytes, String name, String description,
                                       List<String> providers, long tenantId, long userId,
                                       String idempotencyKey) {
        SkillDO existing = skillDao.findById(id);
        if (existing == null || existing.getTenantId() == null || !existing.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        String type = normalizePackageType(existing.getType());
        ParsedPackage parsed = parseByType(type, fileName, bytes, name, description);
        String packageMd5 = digest(parsed.bytes, "MD5");
        if (existing.getPackageMd5() != null && existing.getPackageMd5().equalsIgnoreCase(packageMd5)) {
            return skillService.get(id);
        }
        SkillDO duplicate = skillDao.findByTypeAndName(tenantId, type, parsed.name);
        if (duplicate != null && !duplicate.getId().equals(id)) {
            throw new BizException(ErrorCode.SKILL_DUPLICATE_NAME);
        }
        StoredObject stored = putPackage(tenantId, id, parsed.bytes);
        updatePackageRecord(id, tenantId, type, providers, parsed, stored, existing.getVersion(), userId);
        return skillService.get(id);
    }

    private SkillPackageInspectVO inspect(ParsedPackage parsed) {
        SkillPackageInspectVO vo = new SkillPackageInspectVO();
        vo.setName(parsed.name);
        vo.setDescription(parsed.description);
        vo.setFileName(parsed.fileName);
        vo.setPackageSize((long) parsed.bytes.length);
        return vo;
    }

    private void updatePackageRecord(Long id, long tenantId, String type, List<String> providers,
									 ParsedPackage parsed, StoredObject stored,
                                     Integer version, long userId) {
        int rows = skillDao.updatePackage(id, tenantId, type, installSpec(type, providers), parsed.name, parsed.description, SOURCE_TYPE_OSS_ZIP,
                stored.getOssRef(), parsed.fileName, stored.getSize(), stored.getMd5(), version, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SKILL_VERSION_CONFLICT);
        }
    }

    private ParsedPackage parsePlugin(MultipartFile file, String name, String description) {
        try {
            return parsePlugin(file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getBytes(), name, description);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw invalid();
        }
    }

    private ParsedPackage parsePlugin(String fileName, byte[] bytes, String name, String description) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_SIZE
                || name == null || name.isBlank()) {
            throw invalid();
        }
        String suffix = packageSuffix(fileName);
        int files = ".tar.gz".equals(suffix) ? validateTarGz(bytes) : validateZip(bytes);
        if (files == 0) {
            throw invalid();
        }
        String normalizedName = name.trim();
        return new ParsedPackage(normalizedName, description == null ? "" : description.trim(),
                normalizedFileName(normalizedName, suffix), bytes);
    }

    private int validateZip(byte[] bytes) {
        int entries = 0;
        int files = 0;
        long inflatedSize = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw invalid();
                }
                validateEntryName(entry.getName());
                if (!entry.isDirectory()) {
                    files++;
                    int read;
                    while ((read = zis.read(buffer)) >= 0) {
                        inflatedSize += read;
                        if (inflatedSize > MAX_PACKAGE_SIZE) {
                            throw invalid();
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw invalid();
        }
        return files;
    }

	private static String normalizePackageType(String type) {
		String value = type == null ? SKILL_TYPE : type.trim().toUpperCase();
		if (!SKILL_TYPE.equals(value) && !"PLUGIN".equals(value) && !HOOK_TYPE.equals(value)) { throw invalid(); }
		return value;
	}

	private static String installSpec(String type, List<String> providers) {
		if (SKILL_TYPE.equals(type) || HOOK_TYPE.equals(type)) {
            return JSON.toJSONString(Map.of("source", SOURCE_TYPE_OSS_ZIP));
        }
		if (providers == null || providers.isEmpty()) { throw invalid(); }
		List<String> normalized = providers.stream().map(value -> value.trim().toLowerCase(Locale.ROOT))
				.distinct().collect(Collectors.toList());
		if (normalized.isEmpty() || !DIRECT_PLUGIN_PROVIDERS.containsAll(normalized)) { throw invalid(); }
		return JSON.toJSONString(Map.of("source", SOURCE_TYPE_OSS_ZIP, "providers", normalized));
	}

    private StoredObject putPackage(long tenantId, long skillId, byte[] bytes) {
        String key = "t/" + tenantId + "/skills/" + skillId + "/skill.zip";
        return storage.put(bucket, key, bytes);
    }

    private ParsedPackage parse(MultipartFile file) {
        try {
            return parse(file == null ? null : file.getOriginalFilename(),
                    file == null ? null : file.getBytes());
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw invalid();
        }
    }

    private ParsedPackage parse(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_SIZE) {
            throw invalid();
        }
        String suffix = packageSuffix(fileName);
        String skillMd = ".tar.gz".equals(suffix) ? readRootSkillMdFromTarGz(bytes) : readRootSkillMd(bytes);
        SkillMetadata metadata = parseFrontmatter(skillMd);
        return new ParsedPackage(metadata.name, metadata.description, normalizedFileName(metadata.name, suffix), bytes);
    }

    private ParsedPackage parseByType(String type, String fileName, byte[] bytes,
                                      String name, String description) {
        if ("PLUGIN".equals(type)) {
            return parsePlugin(fileName, bytes, name, description);
        }
        if (HOOK_TYPE.equals(type)) {
            return parseHook(fileName, bytes, name, description);
        }
        return parse(fileName, bytes);
    }

    private ParsedPackage parseHook(String fileName, byte[] bytes, String requestedName, String description) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_SIZE) {
            throw invalid();
        }
        String suffix = packageSuffix(fileName);
        if (!".zip".equals(suffix)) {
            throw invalid();
        }
        String hookYaml = readRootTextFromZip(bytes, "hook.yaml");
        HookMetadata metadata = parseHookMetadata(hookYaml);
        if (requestedName != null && !requestedName.isBlank()
                && !metadata.name.equals(requestedName.trim())) {
            throw invalid();
        }
        String normalizedDescription = description == null || description.isBlank()
                ? "Runtime lifecycle hook: " + metadata.trigger : description.trim();
        return new ParsedPackage(metadata.name, normalizedDescription,
                normalizedFileName(metadata.name, suffix), bytes);
    }

    private String readRootSkillMd(byte[] bytes) {
        return readRootTextFromZip(bytes, "SKILL.md");
    }

    private String readRootTextFromZip(byte[] bytes, String rootFileName) {
        int count = 0;
        long inflatedSize = 0;
        String rootText = null;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw invalid();
                }
                String name = entry.getName();
                validateEntryName(name);
                if (!entry.isDirectory()) {
                    ByteArrayOutputStreamWithLimit rootBytes = rootFileName.equals(name)
                            ? new ByteArrayOutputStreamWithLimit(MAX_PACKAGE_SIZE) : null;
                    int read;
                    while ((read = zis.read(buffer)) >= 0) {
                        inflatedSize += read;
                        if (inflatedSize > MAX_PACKAGE_SIZE) {
                            throw invalid();
                        }
                        if (rootBytes != null) {
                            rootBytes.write(buffer, 0, read);
                        }
                    }
                    if (rootBytes != null) {
                        rootText = new String(rootBytes.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            throw invalid();
        }
        if (rootText == null) {
            throw invalid();
        }
        return rootText;
    }

    private String readRootSkillMdFromTarGz(byte[] bytes) {
        return readRootTextFromTarGz(bytes, "SKILL.md");
    }

    private String readRootTextFromTarGz(byte[] bytes, String rootFileName) {
        TarGzReadResult result = readTarGz(bytes, rootFileName);
        if (result.rootText == null) {
            throw invalid();
        }
        return result.rootText;
    }

    private int validateTarGz(byte[] bytes) {
        return readTarGz(bytes, null).files;
    }

    private TarGzReadResult readTarGz(byte[] bytes, String rootFileName) {
        int entries = 0;
        int files = 0;
        long inflatedSize = 0;
        String rootText = null;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] header = new byte[512];
            while (readFully(gis, header) == 512) {
                if (isZeroBlock(header)) {
                    break;
                }
                if (++entries > MAX_ENTRIES) {
                    throw invalid();
                }
                String name = tarString(header, 0, 100);
                long size = tarSize(header);
                char type = (char) header[156];
                validateEntryName(name);
                if (type == '2') {
                    throw invalid();
                }
                if (type != '5') {
                    files++;
                    inflatedSize += size;
                    if (inflatedSize > MAX_PACKAGE_SIZE) {
                        throw invalid();
                    }
                    if (rootFileName != null && rootFileName.equals(name)) {
                        byte[] content = gis.readNBytes(Math.toIntExact(size));
                        if (content.length != size) {
                            throw invalid();
                        }
                        rootText = new String(content, StandardCharsets.UTF_8);
                    } else {
                        skipFully(gis, size);
                    }
                    skipFully(gis, tarPadding(size));
                }
            }
        } catch (IOException | ArithmeticException e) {
            throw invalid();
        }
        return new TarGzReadResult(files, rootText);
    }

    private HookMetadata parseHookMetadata(String hookYaml) {
        try {
            Object parsed = new Yaml().load(hookYaml);
            if (!(parsed instanceof Map)) {
                throw invalid();
            }
            Map<?, ?> map = (Map<?, ?>) parsed;
            String schemaVersion = asString(map.get("schemaVersion"));
            String name = asString(map.get("name"));
            String version = asString(map.get("version"));
            String trigger = asString(map.get("trigger"));
            String command = asString(map.get("command"));
            if (!"autowonder.hook.v1".equals(schemaVersion)
                    || name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || version == null || version.isBlank()
                    || trigger == null || !HOOK_TRIGGERS.contains(trigger)
                    || command == null || command.isBlank()) {
                throw invalid();
            }
            return new HookMetadata(name, trigger);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static int readFully(GZIPInputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long tarSize(byte[] header) {
        String value = tarString(header, 124, 12).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static long tarPadding(long size) {
        long remainder = size % 512;
        return remainder == 0 ? 0 : 512 - remainder;
    }

    private static void skipFully(GZIPInputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw invalid();
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private SkillMetadata parseFrontmatter(String skillMd) {
        if (skillMd == null || !(skillMd.startsWith("---\n") || skillMd.startsWith("---\r\n"))) {
            throw invalid();
        }
        int yamlStart = skillMd.startsWith("---\r\n") ? 5 : 4;
        int end = skillMd.indexOf("\n---", yamlStart);
        if (end < 0) {
            throw invalid();
        }
        try {
            String yamlText = skillMd.substring(yamlStart, end);
            Object parsed = new Yaml().load(yamlText);
            if (!(parsed instanceof Map)) {
                throw invalid();
            }
            Map<?, ?> map = (Map<?, ?>) parsed;
            String name = asString(map.get("name"));
            String description = asString(map.get("description"));
            if (name == null || name.isBlank() || description == null || description.isBlank()) {
                throw invalid();
            }
            return new SkillMetadata(name.trim(), description.trim());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String packageSuffix(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ".zip";
        }
        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".tar.gz")) {
            return ".tar.gz";
        }
        if (normalized.endsWith(".zip")) {
            return ".zip";
        }
        throw invalid();
    }

    private PackageBytes loadUploadedPackage(String packageOssRef, String expectedMd5) {
        if (packageOssRef == null || packageOssRef.isBlank()) {
            throw invalid();
        }
        byte[] bytes = storage.get(packageOssRef.trim());
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_SIZE) {
            throw invalid();
        }
        verifyDigest(bytes, expectedMd5);
        return new PackageBytes(fileNameFromRef(packageOssRef), bytes);
    }

    private static String fileNameFromRef(String packageOssRef) {
        int index = packageOssRef.lastIndexOf('/');
        return index < 0 ? packageOssRef : packageOssRef.substring(index + 1);
    }

    private static byte[] readEntryBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStreamWithLimit out = new ByteArrayOutputStreamWithLimit(MAX_PACKAGE_SIZE);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zis.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void verifyDigest(byte[] bytes, String expectedMd5) {
        if (expectedMd5 != null && !expectedMd5.isBlank()
                && !expectedMd5.trim().equalsIgnoreCase(digest(bytes, "MD5"))) {
            throw invalid();
        }
    }

    private static String digest(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw invalid();
        }
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("..") || name.contains("\\")) {
            throw invalid();
        }
    }

    private static String normalizedFileName(String name) {
        return normalizedFileName(name, ".zip");
    }

    private static String normalizedFileName(String name, String suffix) {
        return name.replaceAll("[^A-Za-z0-9._-]", "-") + suffix;
    }

    private static String chooseBucket(OssProperties props) {
        return props.resolveSkillBucket();
    }

    private static BizException invalid() {
        return new BizException(ErrorCode.PARAM_INVALID);
    }

    public record UploadedPackage(String packageOssRef, String fileName, Long size, String md5, String sha256,
                                  String type, String name, String description) {
    }

    private record PackageBytes(String fileName, byte[] bytes) {
    }

    private record TarGzReadResult(int files, String rootText) {
    }

    private record HookMetadata(String name, String trigger) {
    }

    private static class ByteArrayOutputStreamWithLimit extends java.io.ByteArrayOutputStream {
        private final long limit;

        private ByteArrayOutputStreamWithLimit(long limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if ((long) count + len > limit) {
                throw invalid();
            }
            super.write(b, off, len);
        }
    }

    private static class ParsedPackage {
        private final String name;
        private final String description;
        private final String fileName;
        private final byte[] bytes;

        private ParsedPackage(String name, String description, String fileName, byte[] bytes) {
            this.name = name;
            this.description = description;
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }

    private static class SkillMetadata {
        private final String name;
        private final String description;

        private SkillMetadata(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
