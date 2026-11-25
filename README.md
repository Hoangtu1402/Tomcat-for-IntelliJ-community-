# Tomcat & WAR Support for IntelliJ IDEA Community

Plugin bổ sung cho **IntelliJ IDEA Community 2025.3+** những tính năng web/Tomcat vốn chỉ có ở bản Ultimate:

| Tính năng | Mô tả |
|---|---|
| **Web facet** | Cùng type id (`web`) và định dạng XML với Ultimate. Tự phát hiện `WEB-INF/web.xml` (thông báo "Frameworks detected"). |
| **Artifact `Web Application: Exploded`** | Type id `exploded-war`, giống Ultimate. Có template "From module '…'" trong *Project Structure › Artifacts*. |
| **Artifact `Web Application: Archive`** | Type id `war`, đóng gói `.war` từ artifact exploded. |
| **Tự tạo artifact cho Maven/Gradle** | Module có `src/main/webapp` sẽ tự có Web facet + `<module>:war exploded` và `<module>:war`. `WEB-INF/lib` tự cập nhật khi dependency thay đổi (bỏ qua `provided`/`compileOnly` và `test`). |
| **Run configuration `Tomcat Server › Local`** | Run & Debug, chọn artifact + context path, cổng HTTP/JMX, VM options, biến môi trường, JRE riêng, mở trình duyệt sau khi khởi động, tab log *Tomcat Localhost Log* / *Catalina Log*. Mỗi cấu hình dùng một `CATALINA_BASE` riêng, không động vào thư mục Tomcat gốc. |
| **Update application (Ctrl+F10)** | *Update resources*, *Update classes and resources* (HotSwap khi debug), *Redeploy* (reload context qua JMX), *Restart server*. Có tuỳ chọn tự cập nhật khi IDE mất focus (*On frame deactivation*). |
| **Update từng module** | Khi deploy nhiều artifact, Ctrl+F10 (hoặc *Update Tomcat Application…*, Ctrl+Alt+F10) cho chọn: tất cả, ứng dụng chứa module của file đang sửa, hoặc một artifact bất kỳ. Chỉ artifact đó được build và chỉ context đó được reload. |
| **Services view** | Nhóm **Tomcat** riêng trong cửa sổ *Services* (Alt+8), giống Ultimate: bên trái là cây server → artifact; bên phải là tab **Output** (console của Tomcat) và các tab log (*Tomcat Localhost Log*, *Catalina Log*, …) cập nhật theo thời gian thực. Artifact: Update resources / Update classes and resources / Redeploy **chỉ riêng artifact đó**, Open in Browser, Build Artifact. |
| **Giao diện cấu hình như Ultimate** | Các tab *Server* (application server, Open browser + chọn trình duyệt, VM options, On 'Update' action + Show dialog, On frame deactivation, JRE, cổng HTTP/HTTPs/JMX/AJP, Deploy applications configured in Tomcat instance, Preserve sessions), *Deployment* (danh sách artifact + Application context), *Logs*, *Startup/Connection*. Hộp thoại *Update* giống Ultimate, thêm lựa chọn ứng dụng cần update. |
| **Log ra file & reset log (tuỳ chọn, mặc định tắt)** | Tab *Logs*: *Save console output to file* ghi console ra file theo thời gian thực (file được ghi lại từ đầu mỗi lần chạy); *Log directory* cho catalina/localhost/access log; *Clear old Tomcat log files on every start*; các tab log Tomcat trong Services. Tất cả mặc định **tắt**; nếu cấu hình sai (thư mục không hợp lệ, không ghi được…) Tomcat vẫn chạy bình thường, plugin chỉ in cảnh báo và dùng thư mục mặc định. |
| **Tương thích dự án tạo bằng Ultimate** | Đọc được Web facet, artifact `exploded-war`/`war` (kể cả phần tử `'Web' facet resources`) và run configuration Tomcat của Ultimate. Plugin có phần mở rộng cho tiến trình build (JPS) nên các artifact này build được trên Community. |

Hỗ trợ Tomcat 7 – 11 (`javax.*` lẫn `jakarta.*`).

### Phiên bản Java

Plugin chạy bên trong IntelliJ (luôn dùng Java 21 đi kèm IDE), còn Tomcat chạy bằng **Project SDK** hoặc *JRE* chọn trong tab *Server*. Mỗi bản Tomcat cần Java tối thiểu:

| Tomcat | Java tối thiểu | Servlet API | Chạy với Java 11? |
|---|---|---|---|
| 9.0 | 8 | `javax.*` | ✔ (đã kiểm tra với 9.0.124) |
| 10.1 | 11 | `jakarta.*` | ✔ (đã kiểm tra với 10.1.62) |
| 11.0 | 17 | `jakarta.*` | ✘ — plugin báo lỗi ngay khi cấu hình |

Dùng **Tomcat 9.0** nếu ứng dụng dùng `javax.servlet`, **Tomcat 10.1** nếu dùng `jakarta.servlet`.

## Cài đặt

1. Build plugin (cần JDK 21):
   ```bash
   ./gradlew buildPlugin
   ```
   File cài đặt: `build/distributions/tomcat-community-1.0.0.zip`
2. Trong IntelliJ: **Settings › Plugins › ⚙ › Install Plugin from Disk…**, chọn file zip, rồi khởi động lại IDE.

## Sử dụng

1. **Khai báo Tomcat**: *Settings › Build, Execution, Deployment › Tomcat Servers* › `+` › chọn thư mục Tomcat (CATALINA_HOME).
2. **Artifact**:
   - Dự án Maven/Gradle có `src/main/webapp`: artifact `ten-module:war exploded` và `ten-module:war` được tạo tự động sau khi mở/import dự án.
   - Dự án khác: *File › Project Structure › Artifacts* › `+` › *Web Application: Exploded* › *From module '…'*.
3. **Run configuration**: *Run › Edit Configurations…* › `+` › **Tomcat Server › Local**.
   - Tab *Server*: chọn Tomcat, cổng HTTP, URL mở trình duyệt, hành động khi *Update*.
   - Tab *Deployment*: `+` để thêm artifact và đặt context path (mặc định `/ten_artifact`). Bước *Build artifact* trước khi chạy được tự thêm.
4. Bấm **Run** hoặc **Debug**. Khi đang chạy, sửa code rồi bấm **Ctrl+F10** (*Run › Update Tomcat Application*) để cập nhật mà không cần khởi động lại.
   - Nếu cấu hình deploy nhiều artifact, một popup hiện ra: chọn *All applications*, *Applications containing module '…'* (module của file đang mở, được chọn sẵn) hoặc một artifact cụ thể, rồi chọn hành động (Update resources / Update classes and resources / Redeploy).
   - *Run › Update Tomcat Application…* (**Ctrl+Alt+F10**) luôn hỏi, kể cả khi chỉ có một artifact.
5. **Services** (*View › Tool Windows › Services*, Alt+8), nhóm **Tomcat**:
   ```
   Tomcat
    └─ Tomcat 10.1   :8080  [running]
        ├─ shop:war exploded    /shop   [deployed]
        └─ admin:war exploded   /admin  [deployed]
   ```
   - Toolbar của node server: Run, Debug, Stop, Restart, Update tất cả, Show in Run Tool Window, Edit Configuration.
   - Chọn node artifact: Update resources, Update classes and resources, Redeploy chỉ áp dụng cho artifact đó. Nhấp đúp để mở trình duyệt.
   - Chọn node server: bên phải hiện tab **Output** (console) và các tab log Tomcat, cập nhật theo thời gian thực. Khi Tomcat khởi động, Services tự mở và chọn server đó.

## Cấu trúc mã nguồn

```
src/main/kotlin/io/github/hoangtu/tomcat/
  web/       Web facet, framework detector, tự cấu hình module Maven/Gradle
  artifact/  Artifact types exploded-war / war, phần tử 'Web' facet resources, layout WAR
  server/    Danh sách Tomcat đã khai báo + trang Settings
  run/       Run configuration, khởi chạy Tomcat, CATALINA_BASE, Update/Redeploy qua JMX
jps-plugin/  Phần mở rộng cho tiến trình build (JPS): facet "web" và phần tử javaee-facet-resources
src/test/    Test: đọc/ghi cấu hình, đồng bộ artifact, build JPS, chạy Tomcat thật
```

Chạy test (test khởi chạy Tomcat thật chỉ chạy khi có `TOMCAT_HOME`):

```bash
TOMCAT_HOME=/path/to/apache-tomcat-10.1.x ./gradlew test
```

## Giới hạn

- Chỉ hỗ trợ **Tomcat local**: không có remote server, Docker hay Tomcat nhúng.
- **Không có hỗ trợ ngôn ngữ** cho JSP/Servlet/Jakarta EE (tô màu, autocomplete JSP, kiểm tra `web.xml`, …). Plugin lo phần đóng gói, triển khai và chạy/debug.
- Với run configuration tạo từ Ultimate, plugin đọc server, artifact, context path, URL trình duyệt, chính sách update và VM options. Cổng HTTP được lấy từ URL trình duyệt; các tuỳ chọn riêng khác của Ultimate bị bỏ qua.
- Tên Tomcat trong run configuration phải trùng với tên khai báo ở *Tomcat Servers* (giống *Application Servers* của Ultimate). Nếu chưa có, bấm *Fix* trong hộp thoại cấu hình.
- Redeploy dùng JMX (mặc định cổng 1099). Nếu không kết nối được, plugin "touch" file context để Tomcat tự redeploy, có thể mất vài giây.
