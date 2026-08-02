package fan.summer.fengyu.web.controller;

import fan.summer.fengyu.ai.ChatFileContext.ActiveFileRef;
import fan.summer.fengyu.ai.ChatFileGrantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Chat attachment endpoints that fan one explicit user selection out to eligible backend plugins. */
@RestController
@RequestMapping("/api/ai/files")
public class AiFileController {
    private final ChatFileGrantService grants;

    public AiFileController(ChatFileGrantService grants) {
        this.grants = grants;
    }

    @PostMapping("/native")
    public List<ActiveFileRefDto> nativeGrant(@RequestBody NativeGrant request) {
        return dto(grants.grantNative(request.path(), request.kind(), request.writableDirectory()));
    }

    @PostMapping("/upload")
    public ResponseEntity<List<ActiveFileRefDto>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dto(grants.grantUpload(file)));
    }

    @PostMapping("/upload-directory")
    public ResponseEntity<List<ActiveFileRefDto>> uploadDirectory(
            @RequestPart("files") List<MultipartFile> uploads,
            @RequestParam("paths") List<String> paths,
            @RequestParam(value = "writable", defaultValue = "true") boolean writable) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(dto(grants.grantUploadDirectory(uploads, paths, writable)));
    }

    @PostMapping("/revoke")
    public void revoke(@RequestBody RevokeRequest request) {
        grants.revoke(request.pluginId(), request.refId());
    }

    private static List<ActiveFileRefDto> dto(List<ActiveFileRef> refs) {
        return refs.stream().map(ref -> new ActiveFileRefDto(ref.pluginId(), ref.ref())).toList();
    }

    public record NativeGrant(String path, String kind, boolean writableDirectory) {}
    public record RevokeRequest(String pluginId, String refId) {}
    public record ActiveFileRefDto(String pluginId,
                                   fan.summer.fengyu.plugin.runtime.PluginFileGrantService.FileRef ref) {}
}
