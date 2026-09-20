package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillPackageFileContentVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFileVO;
import com.aliyun.autowonder.skill.dto.SkillPackageFilesVO;
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.alibaba.fastjson.JSON;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    static final String KIND_DIR = "DIR";
    static final String KIND_TEXT = "TEXT";
    static final String KIND_IMAGE = "IMAGE";
    static final String KIND_BINARY = "BINARY";
    static final String FORMAT_ZIP = "zip";
    static final String FORMAT_TAR_GZ = "tar.gz";
    private static final int SNIFF_LIMIT = 8192;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "mdx", "txt", "text", "log",
            "yaml", "yml", "json", "toml", "ini", "cfg", "conf", "properties", "env",
            "xml", "html", "htm", "css", "scss", "less", "csv", "tsv",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
            "py", "rb", "sh", "bash", "zsh", "fish", "bat", "ps1",
            "java", "kt", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "scala",
            "sql", "graphql", "proto", "gitignore", "gitattributes", "editorconfig");

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

    /** Directory contents use paths relative to the selected root, never server filesystem paths. */
    public byte[] packDirectory(Map<String, String> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_ENTRIES) {
            throw invalid();
        }
        ByteArrayOutputStreamWithLimit output = new ByteArrayOutputStreamWithLimit(MAX_PACKAGE_SIZE);
        long totalSize = 0;
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            // Stable order and timestamps preserve package hashes across retries.
            for (String path : files.keySet().stream().sorted().toList()) {
                validateEntryName(path);
                if (path.endsWith("/") || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals("."))) {
                    throw invalid();
                }
                String encoded = files.get(path);
                if (encoded == null || encoded.length() > 4 * ((MAX_PACKAGE_SIZE - totalSize + 2) / 3)) {
                    throw invalid();
                }
                byte[] bytes = Base64.getDecoder().decode(encoded);
                totalSize += bytes.length;
                if (totalSize > MAX_PACKAGE_SIZE) {
                    throw invalid();
                }
                ZipEntry entry = new ZipEntry(path);
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(bytes);
                zip.closeEntry();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw invalid();
        }
        return output.toByteArray();
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

    /**
     * 只读列出技能包内条目清单（隐式目录自动补齐）。实时从对象存储取回整包并流式解析，
     * 不落盘、不写库、不加缓存，因此本能力上线前上传的存量技能同样可查。
     *
     * 不复用 parse()/parseByType()：那条路径强制要求根 SKILL.md，PLUGIN 与 HOOK 包并不满足。
     * 这里只复用底层的 zip / tar.gz 迭代与上传侧同一套资源防护。
     */
    public SkillPackageFilesVO listPackageFiles(SkillVO skill) {
        PackageRef ref = packageRef(skill);
        List<SkillPackageFileVO> files = ".tar.gz".equals(ref.suffix())
                ? listTarGzEntries(ref.bytes())
                : listZipEntries(ref.bytes());
        return new SkillPackageFilesVO(files, format(ref.suffix()));
    }

    /** 只读读取包内单个文本文件的完整内容；单次只展开被命中的那个条目，其余条目跳过不解压。 */
    public SkillPackageFileContentVO readPackageFile(SkillVO skill, String path) {
        // 先校验路径再回源：非法路径不应触发任何 OSS 读取
        String target = normalizeRequestedPath(path);
        PackageRef ref = packageRef(skill);
        byte[] content = ".tar.gz".equals(ref.suffix())
                ? readTarGzEntry(ref.bytes(), target)
                : readZipEntry(ref.bytes(), target);
        if (content == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "包内不存在该文件: " + target);
        }
        if (!KIND_TEXT.equals(resolveKind(target, sniffWindow(content)))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该文件不支持在线预览");
        }
        return new SkillPackageFileContentVO(target, entryName(target), decodeUtf8(content));
    }

    /** 取回原始技能包字节，供下载端点按原格式（zip / tar.gz）下发。 */
    public PackageDownload loadPackage(SkillVO skill) {
        PackageRef ref = packageRef(skill);
        return new PackageDownload(ref.fileName(), format(ref.suffix()), ref.bytes());
    }

    private PackageRef packageRef(SkillVO skill) {
        if (skill == null || !SOURCE_TYPE_OSS_ZIP.equals(skill.getSourceType())
                || skill.getPackageOssRef() == null || skill.getPackageOssRef().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该技能无上传包");
        }
        String ossRef = skill.getPackageOssRef().trim();
        byte[] bytes = storage.get(ossRef);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_PACKAGE_SIZE) {
            throw invalid();
        }
        String fileName = skill.getPackageFileName() == null || skill.getPackageFileName().isBlank()
                ? fileNameFromRef(ossRef) : skill.getPackageFileName().trim();
        return new PackageRef(ossRef, fileName, resolveSuffix(fileName, bytes), bytes);
    }

    private List<SkillPackageFileVO> listZipEntries(byte[] bytes) {
        List<SkillPackageFileVO> files = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        int entries = 0;
        long inflatedSize = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw tooManyEntries();
                }
                validateEntryName(entry.getName());
                String path = normalizeEntryPath(entry.getName());
                if (path.isEmpty()) {
                    continue;
                }
                if (entry.isDirectory()) {
                    addDirectory(files, listed, path);
                    continue;
                }
                boolean sniff = kindByExtension(path) == null;
                byte[] head = null;
                long size = 0;
                int read;
                while ((read = zis.read(buffer)) >= 0) {
                    if (sniff && size < SNIFF_LIMIT) {
                        head = concat(head, buffer, (int) Math.min(read, SNIFF_LIMIT - size));
                    }
                    size += read;
                    inflatedSize += read;
                    if (inflatedSize > MAX_PACKAGE_SIZE) {
                        throw tooLarge();
                    }
                }
                addDirectory(files, listed, parentPath(path));
                files.add(new SkillPackageFileVO(path, entryName(path), false, size, resolveKind(path, head)));
                listed.add(path);
            }
        } catch (IOException e) {
            throw invalid();
        }
        return files;
    }

    private List<SkillPackageFileVO> listTarGzEntries(byte[] bytes) {
        List<SkillPackageFileVO> files = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        int entries = 0;
        long inflatedSize = 0;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] header = new byte[512];
            while (readFully(gis, header) == 512) {
                if (isZeroBlock(header)) {
                    break;
                }
                if (++entries > MAX_ENTRIES) {
                    throw tooManyEntries();
                }
                String raw = tarString(header, 0, 100);
                long size = tarSize(header);
                char type = (char) header[156];
                validateEntryName(raw);
                if (type == '2') {
                    throw invalid();
                }
                String path = normalizeEntryPath(raw);
                byte[] head = null;
                if (type != '5') {
                    if (size < 0) {
                        throw invalid();
                    }
                    inflatedSize += size;
                    if (inflatedSize > MAX_PACKAGE_SIZE) {
                        throw tooLarge();
                    }
                    if (!path.isEmpty() && kindByExtension(path) == null) {
                        head = gis.readNBytes((int) Math.min(size, SNIFF_LIMIT));
                        skipFully(gis, size - head.length);
                    } else {
                        skipFully(gis, size);
                    }
                    skipFully(gis, tarPadding(size));
                }
                // 名字规范化后为空的条目仍须先消费完 payload 再丢弃，否则 512 字节头会错位
                if (path.isEmpty()) {
                    continue;
                }
                if (type == '5') {
                    addDirectory(files, listed, path);
                    continue;
                }
                addDirectory(files, listed, parentPath(path));
                files.add(new SkillPackageFileVO(path, entryName(path), false, size, resolveKind(path, head)));
                listed.add(path);
            }
        } catch (IOException | ArithmeticException e) {
            throw invalid();
        }
        return files;
    }

    private byte[] readZipEntry(byte[] bytes, String targetPath) {
        int entries = 0;
        long inflatedSize = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw tooManyEntries();
                }
                validateEntryName(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                if (normalizeEntryPath(entry.getName()).equals(targetPath)) {
                    byte[] content = readEntryBytes(zis);
                    if (inflatedSize + content.length > MAX_PACKAGE_SIZE) {
                        throw tooLarge();
                    }
                    return content;
                }
                // 跳过的条目也要累加解压量：单条目上限之外还需与清单路径同款的全局防线
                int read;
                while ((read = zis.read(buffer)) >= 0) {
                    inflatedSize += read;
                    if (inflatedSize > MAX_PACKAGE_SIZE) {
                        throw tooLarge();
                    }
                }
            }
        } catch (IOException e) {
            throw invalid();
        }
        return null;
    }

    private byte[] readTarGzEntry(byte[] bytes, String targetPath) {
        int entries = 0;
        long inflatedSize = 0;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] header = new byte[512];
            while (readFully(gis, header) == 512) {
                if (isZeroBlock(header)) {
                    break;
                }
                if (++entries > MAX_ENTRIES) {
                    throw tooManyEntries();
                }
                String raw = tarString(header, 0, 100);
                long size = tarSize(header);
                char type = (char) header[156];
                validateEntryName(raw);
                if (type == '2') {
                    throw invalid();
                }
                if (type == '5') {
                    continue;
                }
                if (size < 0 || size > MAX_PACKAGE_SIZE) {
                    throw invalid();
                }
                // tar 头已声明 size，跳过的条目同样计入全局解压总量
                inflatedSize += size;
                if (inflatedSize > MAX_PACKAGE_SIZE) {
                    throw tooLarge();
                }
                if (normalizeEntryPath(raw).equals(targetPath)) {
                    byte[] content = gis.readNBytes(Math.toIntExact(size));
                    if (content.length != size) {
                        throw invalid();
                    }
                    return content;
                }
                skipFully(gis, size);
                skipFully(gis, tarPadding(size));
            }
        } catch (IOException | ArithmeticException e) {
            throw invalid();
        }
        return null;
    }

    static void addDirectory(List<SkillPackageFileVO> files, Set<String> listed, String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        String current = dirPath;
        while (!current.isEmpty() && listed.add(current)) {
            missing.add(current);
            current = parentPath(current);
        }
        Collections.reverse(missing);
        for (String path : missing) {
            files.add(new SkillPackageFileVO(path, entryName(path), true, 0L, KIND_DIR));
        }
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private static String entryName(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : path.substring(index + 1);
    }

    private static String normalizeEntryPath(String name) {
        String path = name.trim();
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 路径双闸门第一道：先过与上传校验同款的条目名规则，拒绝 ..、绝对路径与反斜杠。 */
    private static String normalizeRequestedPath(String path) {
        validateEntryName(path);
        String normalized = normalizeEntryPath(path);
        if (normalized.isEmpty()) {
            throw invalid();
        }
        return normalized;
    }

    static String resolveKind(String path, byte[] head) {
        String byExtension = kindByExtension(path);
        return byExtension != null ? byExtension : (looksTextual(head) ? KIND_TEXT : KIND_BINARY);
    }

    private static String kindByExtension(String path) {
        String extension = extension(path);
        if (extension.isEmpty()) {
            return null;
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return KIND_IMAGE;
        }
        return TEXT_EXTENSIONS.contains(extension) ? KIND_TEXT : null;
    }

    private static String extension(String path) {
        String name = entryName(path).toLowerCase(Locale.ROOT);
        int index = name.lastIndexOf('.');
        if (index < 0) {
            return "";
        }
        // 点文件（如 .gitignore）没有"主名.扩展名"结构，去掉前导点后整体即标识
        return index == 0 ? name.substring(1) : name.substring(index + 1);
    }

    /** 嗅探窗口封顶：分类只读前 SNIFF_LIMIT 字节，避免大文件被整段扫描或分配等长 CharBuffer。 */
    private static byte[] sniffWindow(byte[] content) {
        if (content.length <= SNIFF_LIMIT) {
            return content;
        }
        return Arrays.copyOf(content, SNIFF_LIMIT);
    }

    private static boolean looksTextual(byte[] head) {
        if (head == null || head.length == 0) {
            return true;
        }
        for (byte value : head) {
            int unsigned = value & 0xff;
            if (unsigned == 0) {
                return false;
            }
            if (unsigned < 0x09 || (unsigned > 0x0d && unsigned < 0x20 && unsigned != 0x1b)) {
                return false;
            }
        }
        return decodableAsUtf8(head);
    }

    private static boolean decodableAsUtf8(byte[] head) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // endOfInput=false：嗅探窗口可能截断多字节字符，末尾不完整序列返回 UNDERFLOW 而非错误。
        // REPORT 动作下非法序列通过返回值上报，不会抛异常，必须检查 CoderResult。
        return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length + 1), false).isError();
    }

    /** 非法字节由 String 构造器替换为 U+FFFD，满足"不导致接口失败"。 */
    private static String decodeUtf8(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private static String format(String suffix) {
        return ".tar.gz".equals(suffix) ? FORMAT_TAR_GZ : FORMAT_ZIP;
    }

    private static String resolveSuffix(String fileName, byte[] bytes) {
        if (fileName != null && !fileName.isBlank()) {
            String normalized = fileName.trim().toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".tar.gz")) {
                return ".tar.gz";
            }
            if (normalized.endsWith(".zip")) {
                return ".zip";
            }
        }
        // 存量行的 packageFileName 可能缺失或后缀异常，退回魔数探测
        return isGzip(bytes) ? ".tar.gz" : ".zip";
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b;
    }

    private static byte[] concat(byte[] head, byte[] buffer, int length) {
        byte[] existing = head == null ? new byte[0] : head;
        byte[] merged = new byte[existing.length + length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(buffer, 0, merged, existing.length, length);
        return merged;
    }

    private static BizException tooManyEntries() {
        return new BizException(ErrorCode.PARAM_INVALID, "技能包条目数超过上限 " + MAX_ENTRIES);
    }

    private static BizException tooLarge() {
        return new BizException(ErrorCode.PARAM_INVALID, "技能包解压后大小超过上限 100MB");
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
        skillDao.releaseSoftDeletedName(tenantId, normalizedType, parsed.name);
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
        skillDao.releaseSoftDeletedName(tenantId, type, parsed.name);
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
                || name.contains("..") || name.contains("\\") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
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

    /** 读路径的入参解析结果：一次 OSS 拉取，列目录/读文件/下载三处共用，避免重复回源。 */
    private record PackageRef(String ossRef, String fileName, String suffix, byte[] bytes) {
    }

    public record PackageDownload(String fileName, String format, byte[] bytes) {
    }

    private static class ByteArrayOutputStreamWithLimit extends java.io.ByteArrayOutputStream {
        private final long limit;

        private ByteArrayOutputStreamWithLimit(long limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            if (count >= limit) throw invalid();
            super.write(b);
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
