package com.ciniam.internradar.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 最小 SMTP 客户端。依赖 JDK 自带的 SSL，不引入 JavaMail
 * 支持 465 隐式 SSL 与 587 STARTTLS，认证方式为 AUTH LOGIN
 */
@Component
public class SmtpMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private static final String CRLF = "\r\n";
    private static final int LINE_WIDTH = 76;

    private final MailSettings settings;

    public SmtpMailSender(MailSettings settings) {
        this.settings = settings;
    }

    public boolean configured() {
        return settings.configured();
    }

    /**
     * 发一封 HTML 邮件。失败抛异常，由调用方决定是否降级
     */
    public void send(String to, String subject, String html) {
        if (!settings.configured()) {
            throw new IllegalStateException("还没有配置发信邮箱");
        }
        int timeout = Math.max(settings.timeoutSeconds(), 5) * 1000;
        Socket socket = null;
        try {
            socket = connect(settings, timeout);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            expect(reader, 220);
            hello(reader, writer);
            if (!settings.ssl() && settings.starttls()) {
                command(reader, writer, "STARTTLS", 220);
                socket = upgrade(socket, settings, timeout);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                hello(reader, writer);
            }
            auth(reader, writer);
            command(reader, writer, "MAIL FROM:<" + settings.from() + ">", 250);
            command(reader, writer, "RCPT TO:<" + to + ">", 250, 251);
            command(reader, writer, "DATA", 354);
            write(writer, buildMessage(to, subject, html));
            command(reader, writer, ".", 250);
            try {
                command(reader, writer, "QUIT", 221);
            } catch (RuntimeException ignored) {
                // 退出阶段的异常不影响投递结果
            }
            log.info("验证码邮件已投递，收件人 {}", to);
        } catch (Exception e) {
            throw new IllegalStateException("发送邮件失败：" + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private Socket connect(MailSettings config, int timeout) throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        Socket socket = config.ssl() ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(address, timeout);
        socket.setSoTimeout(timeout);
        if (socket instanceof SSLSocket sslSocket) {
            sslSocket.startHandshake();
        }
        return socket;
    }

    private Socket upgrade(Socket plain, MailSettings config, int timeout) throws IOException {
        SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, config.host(), config.port(), true);
        sslSocket.setSoTimeout(timeout);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private void hello(BufferedReader reader, Writer writer) throws IOException {
        String host = settings.heloName();
        writer.write("EHLO " + host + CRLF);
        writer.flush();
        expect(reader, 250);
    }

    private void auth(BufferedReader reader, Writer writer) throws IOException {
        if (settings.username() == null || settings.username().isBlank()) {
            return;
        }
        command(reader, writer, "AUTH LOGIN", 334);
        command(reader, writer, encode(settings.username()), 334);
        command(reader, writer, encode(settings.password()), 235);
    }

    private String buildMessage(String to, String subject, String html) {
        StringBuilder builder = new StringBuilder();
        builder.append("From: ").append(settings.from()).append(CRLF);
        builder.append("To: ").append(to).append(CRLF);
        builder.append("Subject: =?UTF-8?B?").append(encode(subject)).append("?=").append(CRLF);
        builder.append("MIME-Version: 1.0").append(CRLF);
        builder.append("Content-Type: text/html; charset=UTF-8").append(CRLF);
        builder.append("Content-Transfer-Encoding: base64").append(CRLF);
        builder.append(CRLF);
        builder.append(wrap(encode(html)));
        builder.append(CRLF);
        return builder.toString();
    }

    /**
     * DATA 阶段的正文按行发送，行首的点要转义，最后补一个单独的点表示结束
     */
    private void write(Writer writer, String message) throws IOException {
        for (String line : message.split(CRLF, -1)) {
            String safe = line.startsWith(".") ? "." + line : line;
            writer.write(safe + CRLF);
        }
        writer.write("." + CRLF);
        writer.flush();
    }

    private void command(BufferedReader reader, Writer writer, String text, int... expected) throws IOException {
        writer.write(text + CRLF);
        writer.flush();
        expect(reader, expected);
    }

    private void expect(BufferedReader reader, int... expected) throws IOException {
        String line = readResponse(reader);
        int code = codeOf(line);
        for (int candidate : expected) {
            if (code == candidate) {
                return;
            }
        }
        throw new IOException("SMTP 返回异常：" + line);
    }

    /**
     * 多行响应以「码-」开头继续，「码 」结束
     */
    private String readResponse(BufferedReader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append(' ');
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
        }
        if (builder.isEmpty()) {
            throw new IOException("连接被关闭，没有收到响应");
        }
        return builder.toString().trim();
    }

    private int codeOf(String response) {
        try {
            return Integer.parseInt(response.substring(0, 3));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String wrap(String base64) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < base64.length(); i += LINE_WIDTH) {
            builder.append(base64, i, Math.min(i + LINE_WIDTH, base64.length())).append(CRLF);
        }
        return builder.toString();
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
