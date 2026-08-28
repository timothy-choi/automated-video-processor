/** Direct PUT to a presigned object-store URL. Do not send the Account API key. */

export const DEFAULT_MEDIA_UPLOAD_MAX_BYTES = 2 * 1024 * 1024 * 1024;

export class StorageUploadError extends Error {
  readonly aborted: boolean;

  constructor(message: string, aborted = false) {
    super(message);
    this.name = "StorageUploadError";
    this.aborted = aborted;
  }
}

export function putFileToPresignedUrl(options: {
  url: string;
  file: Blob;
  headers?: Record<string, string>;
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", options.url);
    for (const [name, value] of Object.entries(options.headers ?? {})) {
      if (name.toLowerCase() === "authorization") {
        continue;
      }
      xhr.setRequestHeader(name, value);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && options.onProgress) {
        options.onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
        return;
      }
      reject(new StorageUploadError("Upload to object storage failed."));
    };
    xhr.onerror = () => {
      reject(new StorageUploadError("Upload to object storage failed."));
    };
    xhr.onabort = () => {
      reject(new StorageUploadError("Upload cancelled.", true));
    };
    if (options.signal) {
      if (options.signal.aborted) {
        xhr.abort();
        return;
      }
      options.signal.addEventListener("abort", () => xhr.abort(), { once: true });
    }
    xhr.send(options.file);
  });
}
