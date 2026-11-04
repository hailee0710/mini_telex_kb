# Mini Qwerty Keyboard

Bàn phím QWERTY 3 hàng gọn nhẹ cho Android (IME) tích hợp sẵn bộ gõ tiếng Việt **Telex** và chế độ **Smart Telex** thông minh. Bộ gõ là engine của [Vikey](https://github.com/tonyminibot/Vikey) (dựa trên OpenKey), được biên dịch từ mã nguồn vào ứng dụng.

**Không cần quyền nào. Không kết nối mạng.** Bàn phím chỉ đọc những gì bạn gõ và không gửi đi đâu.

## Tính năng

- **Smart Telex** — gõ tiếng Việt kiểu Telex, còn từ tiếng Anh hiện nguyên chữ. Kiểm tra hình dạng âm tiết cùng dữ liệu từ của engine và chế độ tự chuyển ngôn ngữ giữ tiếng Anh đúng chữ; gõ tiếng Việt tiếp theo sẽ tự chuyển lại.
- **Đầy đủ Telex** — cả 5 phím dấu, mọi tổ hợp nguyên âm, đặt dấu đúng chính tả, dấu giữa từ, gõ ba lần để hoàn tác.
- **Tự viết hoa** — chữ đầu câu được viết hoa, phím Shift sáng lên để báo.
- **QWERTY 3 hàng gọn** — 9 chữ cái hiếm nhất là phím phụ (chạm hai lần nhanh) nằm đúng vị trí QWERTY quen thuộc; phím dấu `X S F R J` đặt nơi người gõ Telex mong đợi.
- **Hàng số/ký tự** kèm ký tự phụ khi chạm hai lần.
- **Giao diện sáng/tối/theo hệ thống**, tùy chọn rung, kéo để chỉnh độ cao bàn phím, tùy chỉnh cửa sổ chạm đúp.
- **Độ chính xác khi gõ** — vùng nhấn tính theo phím gần nhất kèm bù vị trí ngón cái, rung ngay khi chạm, xử lý đa chạm để gõ nhanh không mất phím.
- **Không cần quyền đặc biệt** — không có gì để cấp, không có gì để lộ.

## Cài đặt

Tải `app-release.apk` mới nhất từ trang [Releases](https://github.com/hailee0710/mini_qwerty_kb/releases) và mở trên thiết bị.

Sau đó bật bàn phím: **Cài đặt → Hệ thống → Ngôn ngữ và nhập liệu → Bàn phím trên màn hình → Mini Qwerty** (màn hình cài đặt của ứng dụng có nút tắt mở nhanh).

## Gõ tiếng Việt kiểu Telex

Phím dấu:

| Phím | Dấu | Ví dụ |
|------|-----|-------|
| `s` | sắc | `masy` → `máy` |
| `f` | huyền | `maf` → `mà` |
| `r` | hỏi | `mar` → `mả` |
| `x` | ngã | `max` → `mã` |
| `j` | nặng | `maj` → `mạ` |

Tổ hợp nguyên âm (gõ hai lần cùng phím hoặc đúng chuỗi):

```
aa → â    ee → ê    oo → ô    aw → ă
ow → ơ    uw → ư    dd → đ    uow → ươ
```

Các âm đặc biệt: `iê` = `iee`, `uô` = `uoo`, `yê` = `yee`, `uâ` = `uaa`, `uyê` = `uyee`, `ươ` = `uow`.

Gõ thêm lần thứ ba chữ cái cuối của tổ hợp để hoàn tác — từ trở về dạng thường:
`dooor` → `door`. Từ tiếng Anh không cần hoàn tác: khi bật Smart Telex, từ được trả về
đúng các phím đã gõ khi kết thúc từ (`good`, `for`, `bus` giữ nguyên).

Gõ hai lần cùng phím dấu để bỏ dấu; gõ phím dấu khác sẽ thay dấu cũ.

## Smart Telex

Khi bật Smart Telex, kết quả không phải âm tiết tiếng Việt hợp lệ sẽ được trả về đúng các phím
đã gõ ngay khi kết thúc từ, nên `cluster`, `good`, `pool`, `fix`, `door`, `bus`, `this`,
`carry`, `office`, `password` đều hiện nguyên chữ. Chế độ tự chuyển ngôn ngữ của engine cũng
tự đi giữa tiếng Việt và tiếng Anh — từ tiếng Anh gõ sau tiếng Việt vẫn là tiếng Anh, và từ
tiếng Việt tiếp theo tự chuyển lại.

Khi tắt Smart Telex, bàn phím là Telex thuần: mọi phép biến đổi đều áp dụng, không kiểm tra
hình dạng và không tự chuyển ngôn ngữ, nên `fix` thành `fĩ`.

## Bố cục bàn phím

- **Hàng 1:** `X(Q) W(?) E R T H(Y) U I(P) O ,(.)` — phẩy trên, chấm dưới.
- **Hàng 2:** `A S(Z) D F(C) G(V) N(B) J(K) M(L)` + phím xóa.
- **Hàng 3:** shift, `123`, cách, enter.
- **Hàng số:** chữ số, `@ ! % : ) - ? = / ]` kèm ký tự phụ `~ # $ & ( _ + ; ' [`; cùng phím `.` (`,` khi chạm đúp).
- **Clipboard:** dải trên hàng 1 có nút clipboard ở đầu trái — chạm để mở/đóng layer clipboard. Dải hiện mỗi khi lịch sử clipboard có nội dung.

Chạm nhanh hai lần vào phím sẽ thay ký tự cuối bằng ký tự phụ của phím đó. Giữ lâu (350 ms) hoặc vuốt xuống dứt khoát để gõ ký tự phụ. Chạm đúp Shift để bật caps lock (⇪). Giữ phím xóa để xóa liên tục. Gõ nhanh an toàn: hai phím chạm chồng nhau (ngón sau chạm trước khi ngón trước nhấc) vẫn gõ được cả hai, và ngón cái trượt nhẹ khi chạm sẽ không kích hoạt vuốt hay chạm đúp.

## Cài đặt

- Chủ đề: theo hệ thống / sáng / tối / đen (OLED)
- Bật/tắt rung
- Bật/tắt Smart Telex
- Bật/tắt tự viết hoa
- Bật/tắt dải gợi ý clipboard
- Cửa sổ chạm đúp (100–500 ms)

## Biên dịch

Yêu cầu: JDK 17, Android SDK, Gradle 8.5, và — vì engine là mã native — NDK 27.2.12479018 cùng CMake 3.22.1 (`sdkmanager --install "ndk;27.2.12479018" "cmake;3.22.1"`). ABI chỉ gồm `arm64-v8a` và `x86_64`.

```bash
gradle wrapper --gradle-version 8.5   # nếu thiếu ./gradlew (không commit)
./gradlew assembleDebug               # APK debug
./gradlew bundleRelease               # AAB release để phát hành

# kiểm thử engine: build libvikey.so cho máy host rồi chạy cầu nối Kotlin trên JVM
JAVA_HOME=/duong/dan/jdk tools/engine-test/jni-smoke.sh
```

Kiểm thử engine cần JDK có `include/jni.h` (không phải JRE) và `g++`; chạy
`./gradlew assembleDebug` một lần trước để jar Kotlin có trong cache Gradle.

Ký release đọc thuộc tính `miniqwerty.*` từ `~/.gradle/gradle.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); nếu thiếu thì bản release không ký — nên bản clone thuần vẫn biên dịch được.

APK: `app/build/outputs/apk/`, AAB: `app/build/outputs/bundle/release/`.

## Quyền riêng tư

Manifest không khai báo `uses-permission` nào. Không quyền internet, không telemetry, không phân tích. Phím bấm không bao giờ rời khỏi thiết bị.

## Engine và giấy phép

Engine tiếng Việt trong `app/src/main/cpp/` là của [Vikey](https://github.com/tonyminibot/Vikey),
dựa trên [OpenKey](https://github.com/tuyenvm/OpenKey), được đưa nguyên bản từ commit
`783bf95`.

Toàn bộ ứng dụng theo **GPLv3** — xem [LICENSE](LICENSE) và
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Bạn được dùng, nghiên cứu, sửa và phát
hành lại theo cùng giấy phép; bản phát hành phải kèm mã nguồn tương ứng đầy đủ.
