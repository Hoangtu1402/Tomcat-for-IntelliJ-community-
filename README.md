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
| **Tương thích dự án tạo bằng Ultimate** | Đọc được Web facet, artifact `exploded-war`/`war` (kể cả phần tử `'Web' facet resources`) và run configuration Tomcat của Ultimate. Plugin có phần mở rộng cho tiến trình build (JPS) nên các artifact này build được trên Community. |

Hỗ trợ Tomcat 7 – 11 (`javax.*` lẫn `jakarta.*`).

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
