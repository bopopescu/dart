// Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.


class _Directory implements Directory {
  static final kCreateRequest = 0;
  static final kDeleteRequest = 1;
  static final kExistsRequest = 2;
  static final kCreateTempRequest = 3;
  static final kListRequest = 4;

  static final kSuccessResponse = 0;
  static final kIllegalArgumentResponse = 1;
  static final kOSErrorResponse = 2;

  _Directory(String this._path);
  _Directory.current() : _path = _current();

  static String _current() native "Directory_Current";
  static _createTemp(String template) native "Directory_CreateTemp";
  static int _exists(String path) native "Directory_Exists";
  static _create(String path) native "Directory_Create";
  static _delete(String path, bool recursive) native "Directory_Delete";
  static SendPort _newServicePort() native "Directory_NewServicePort";

  void exists(void callback(bool exists)) {
    _ensureDirectoryService();
    List request = new List(2);
    request[0] = kExistsRequest;
    request[1] = _path;
    _directoryService.call(request).then((response) {
      if (_isErrorResponse(response)) {
        _reportError(response, "Exists failed");
      } else {
        callback(response == 1);
      }
    });
  }

  bool existsSync() {
    if (_path is !String) {
      throw new IllegalArgumentException();
    }
    var result = _exists(_path);
    if (result is OSError) {
      throw new DirectoryIOException("Exists failed", _path, result);
    }
    return (result == 1);
  }

  void create(void callback()) {
    _ensureDirectoryService();
    List request = new List(2);
    request[0] = kCreateRequest;
    request[1] = _path;
    _directoryService.call(request).then((response) {
      if (_isErrorResponse(response)) {
        _reportError(response, "Creation failed");
      } else {
        callback();
      }
    });
  }

  void createSync() {
    if (_path is !String) {
      throw new IllegalArgumentException();
    }
    var result = _create(_path);
    if (result is OSError) {
      throw new DirectoryIOException("Creation failed", _path, result);
    }
  }

  void createTemp(void callback()) {
    _ensureDirectoryService();
    List request = new List(2);
    request[0] = kCreateTempRequest;
    request[1] = _path;
    _directoryService.call(request).then((response) {
      if (_isErrorResponse(response)) {
        _reportError(response, "Creation of temporary directory failed");
      } else {
        _path = response;
        callback();
      }
    });
  }

  void createTempSync() {
    if (_path is !String) {
      throw new IllegalArgumentException();
    }
    var result = _createTemp(path);
    if (result is OSError) {
      throw new DirectoryIOException("Creation of temporary directory failed",
                                     _path,
                                     result);
    }
    _path = result;
  }

  void _deleteHelper(bool recursive, String errorMsg, void callback()) {
    _ensureDirectoryService();
    List request = new List(3);
    request[0] = kDeleteRequest;
    request[1] = _path;
    request[2] = recursive;
    _directoryService.call(request).then((response) {
      if (_isErrorResponse(response)) {
        _reportError(response, errorMsg);
      } else {
        callback();
      }
    });
  }

  void delete(void callback()) {
    _deleteHelper(false, "Deletion failed", callback);
  }

  void deleteRecursively(void callback()) {
    _deleteHelper(true, "Deletion failed", callback);
  }

  void deleteSync() {
    if (_path is !String) {
      throw new IllegalArgumentException();
    }
    var result = _delete(_path, false);
    if (result is OSError) {
      throw new DirectoryIOException("Deletion failed", _path, result);
    }
  }

  void deleteRecursivelySync() {
    if (_path is !String) {
      throw new IllegalArgumentException();
    }
    var result = _delete(_path, true);
    if (result is OSError) {
      throw new DirectoryIOException("Deletion failed", _path, result);
    }
  }

  void list([bool recursive = false]) {
    final int kListDirectory = 0;
    final int kListFile = 1;
    final int kListError = 2;
    final int kListDone = 3;

    final int kResponseType = 0;
    final int kResponsePath = 1;
    final int kResponseComplete = 1;
    final int kResponseError = 2;

    List request = new List(3);
    request[0] = kListRequest;
    request[1] = _path;
    request[2] = recursive;
    ReceivePort responsePort = new ReceivePort();
    // Use a separate directory service port for each listing as
    // listing operations on the same directory can run in parallel.
    _newServicePort().send(request, responsePort.toSendPort());
    responsePort.receive((message, replyTo) {
      if (message is !List || message[kResponseType] is !int) {
        responsePort.close();
        if (_onError != null) {
          _onError(new DirectoryIOException("Internal error"));
        }
        return;
      }
      switch (message[kResponseType]) {
        case kListDirectory:
          if (_onDir != null) _onDir(message[kResponsePath]);
          break;
        case kListFile:
          if (_onFile != null) _onFile(message[kResponsePath]);
          break;
        case kListError:
          if (_onError != null) {
            var errorType =
                message[kResponseError][_FileUtils.kErrorResponseErrorType];
            if (errorType == _FileUtils.kIllegalArgumentResponse) {
              _onError(new IllegalArgumentException());
            } else if (errorType == _FileUtils.kOSErrorResponse) {
              var err = new OSError(
                  message[kResponseError][_FileUtils.kOSErrorResponseMessage],
                  message[kResponseError][_FileUtils.kOSErrorResponseErrorCode]);
              _onError(new DirectoryIOException("Directory listing failed",
                                                message[kResponsePath],
                                                err));
            } else {
              _onError(new DirectoryIOException("Internal error"));
            }
          }
          break;
        case kListDone:
          responsePort.close();
          if (_onDone != null) _onDone(message[kResponseComplete]);
          break;
      }
    });
  }

  void set onDir(void onDir(String dir)) {
    _onDir = onDir;
  }

  void set onFile(void onFile(String file)) {
    _onFile = onFile;
  }

  void set onDone(void onDone(bool completed)) {
    _onDone = onDone;
  }

  void set onError(void onError(Exception e)) {
    _onError = onError;
  }

  String get path() { return _path; }

  void _ensureDirectoryService() {
    if (_directoryService == null) {
      _directoryService = _newServicePort();
    }
  }

  bool _isErrorResponse(response) {
    return response is List && response[0] != _FileUtils.kSuccessResponse;
  }

  bool _reportError(response, String message) {
    assert(_isErrorResponse(response));
    if (_onError != null) {
      switch (response[_FileUtils.kErrorResponseErrorType]) {
        case _FileUtils.kIllegalArgumentResponse:
          _onError(new IllegalArgumentException());
          break;
        case _FileUtils.kOSErrorResponse:
          var err = new OSError(response[_FileUtils.kOSErrorResponseMessage],
                                response[_FileUtils.kOSErrorResponseErrorCode]);
          _onError(new DirectoryIOException(message, _path, err));
          break;
        default:
          _onError(new Exception("Unknown error"));
          break;
      }
    }
  }

  Function _onDir;
  Function _onFile;
  Function _onDone;
  Function _onError;

  String _path;
  SendPort _directoryService;
}
