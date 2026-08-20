package com.kkx.ppocrv3.ocr;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCRv3 识别词表。
 * 对应官方 ppocr_keys_v1.txt（6623 个汉字/符号，逐行一个字符）。
 * CTC 的 blank 类别索引 = 词表长度（即最后一个类别）。
 */
public final class Vocab {

    private final List<String> chars = new ArrayList<>();

    public Vocab(String keysFilePath) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(keysFilePath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 逐行一个字符；空行视为占位（多数情况下不会出现）
                chars.add(line);
            }
        }
    }

    /** blank 类别索引（CTC 空白符） */
    public int blankIndex() {
        return chars.size();
    }

    public int size() {
        return chars.size();
    }

    public String charAt(int idx) {
        if (idx < 0 || idx >= chars.size()) return "";
        return chars.get(idx);
    }
}
