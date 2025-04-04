# UDP File Transfer Client-Server (Java-17)

Demo này triển khai một hệ thống truyền file giữa Client - Server sử dụng giao thức UDP bằng ngôn ngữ Java.
Các file không thể gửi trực tiếp giữa các Client mà phải gửi thông qua Server.

## Tính năng
    ClientA gửi một file cho ClientB.
    Server sẽ nhận và lưu trữ file của ClientA trong kho ('server_storage').
    ClientB có thể xem các file được gửi cho mình và tải những file cần về máy ('<client_name>_storage').
    
    Chi nhỏ tệp: các tệp lớn sẽ được chia nhỏ thành các gói tin.
    Có thể điều chỉnh kích thước tệp được chia nhỏ thông qua biến BUFFER_SIZE ở file Client.java và Server.java (1024 * n).

## Setup
    Đặt file Server.java và Client.java cùng một thư mục.
    Đặt file muốn gửi cùng thư mục với các file .java

## Chạy code
    Biên dịch các file .java
        javac Server.java
        javac Client.java

    Chạy file Server.class: java Server

    Chạy file Client để tạo các Client: java Client <client_name>

## Cấu trúc thư mục sau khi chạy
    server_storage: thư mục lưu trữ file trên Server.
    <client_name>_storage: thư mục lưu trữ file được tải về trên Client.

## Các câu lệnh
    send <tên client nhận> <tên file muốn gửi>: gửi file cho Client khác.
    list: hiển thị các file được nhận từ Client khác.
    download <tên file muốn tải>: tải về các file trong 'list'.
    exit: thoát chương trình.