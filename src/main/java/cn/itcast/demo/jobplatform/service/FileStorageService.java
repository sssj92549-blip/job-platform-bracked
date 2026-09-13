package cn.itcast.demo.jobplatform.service;

import cn.itcast.demo.jobplatform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import static cn.itcast.demo.jobplatform.service.BusinessSupport.*;

/** 本地文件存储：随机路径、真实类型验证和受控下载，不信任上传名称或客户端路径。 */
@Service
public class FileStorageService {
    private final Path root;
    public FileStorageService(@Value("${app.storage.root}") String root) { this.root=Path.of(root).toAbsolutePath().normalize(); }
    public record Stored(String path,String name,long size) {}
    public Stored save(MultipartFile file,boolean pdf) {
        if(file.isEmpty()) bad("文件不能为空");
        if(file.getSize()>(pdf?10485760:2097152)) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,41301,"上传文件过大");
        try {
            byte[] bytes=file.getBytes(); String extension;
            if(pdf) {
                if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) bad("请上传有效PDF文件");
                try(var document=Loader.loadPDF(bytes)) {
                    if(document.isEncrypted() || document.getNumberOfPages()<1 || document.getNumberOfPages()>20) bad("PDF须为未加密的1至20页文档");
                }
                extension="pdf";
            } else {
                try(var imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers=ImageIO.getImageReaders(imageInput); if(!readers.hasNext()) bad("仅支持JPEG或PNG图片");
                    var reader=readers.next();
                    try {
                        String format=reader.getFormatName().toLowerCase(Locale.ROOT); if(!Set.of("jpeg","png").contains(format)) bad("仅支持JPEG或PNG图片");
                        reader.setInput(imageInput); if((long)reader.getWidth(0)*reader.getHeight(0)>16000000) bad("图片像素过大");
                        var decoded=reader.read(0); ByteArrayOutputStream clean=new ByteArrayOutputStream(); ImageIO.write(decoded,format,clean); bytes=clean.toByteArray(); extension=format;
                    } finally { reader.dispose(); }
                }
            }
            String relative=(pdf?"resumes/":"avatars/")+UUID.randomUUID()+"."+extension;
            Files.createDirectories(root); Path path=root.resolve(relative); Files.createDirectories(path.getParent());
            if(!path.getParent().toRealPath().startsWith(root.toRealPath())) bad("文件目录无效");
            Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
            String name=Optional.ofNullable(file.getOriginalFilename()).orElse("resume.pdf").replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
            if(name.isBlank()) name="resume.pdf"; if(name.length()>255) name=name.substring(name.length()-255);
            return new Stored(relative,name,bytes.length);
        } catch(BusinessException e) { throw e; }
        catch(IOException e) { throw new BusinessException(HttpStatus.BAD_REQUEST,40001,"文件不可读或存储失败"); }
    }
    /** 仅在Service完成资源归属检查后调用，历史附件不通过静态目录公开。 */
    public ResponseEntity<Resource> download(String relative,String name,boolean pdf) {
        try {
            if(relative==null) { missing(); }
            Path path=root.resolve(relative).normalize();
            if(!path.startsWith(root)||!Files.isRegularFile(path)||!path.toRealPath().startsWith(root.toRealPath())) missing();
            MediaType type=pdf?MediaType.APPLICATION_PDF:relative.endsWith(".png")?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG;
            return ResponseEntity.ok().contentType(type).contentLength(Files.size(path))
                .header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,(pdf?ContentDisposition.attachment():ContentDisposition.inline()).filename(name,StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
        } catch(IOException e) { missing(); return null; }
    }
}
