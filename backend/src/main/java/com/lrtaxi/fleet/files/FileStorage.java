package com.lrtaxi.fleet.files;

import java.io.InputStream;
import java.nio.file.Path;

/** Private object storage. Files are never served publicly; downloads go through an authorised endpoint. */
public interface FileStorage {

    void put(String key, Path source, String contentType);

    InputStream get(String key);
}
