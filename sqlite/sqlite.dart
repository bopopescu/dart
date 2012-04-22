#library("sqlite");

#import("dart-ext:test_extension");

class Connection {
	var _db;
	final String path;

	Connection(path) : this.path = path {
		_db = _new(path);
	}

	static get version() => _version();

	String toString() => "<Sqlite: ${path} (${version})>";

	void close() {
		_checkOpen();
		_close(_db);
		_db = null;
	}

	transaction(callback()) {
		_checkOpen();
		execute("BEGIN");
		try {
			var result = callback();
			execute("COMMIT");
			return result;
		} catch (x) {
			execute("ROLLBACK");
			throw x;
		}
	}

	Statement prepare(String statement) {
		return new Statement._internal(_db, statement);
	}

	int execute(String statement, [params=const [], bool callback(Row)]) {
		statement = prepare(statement);
		try {
			return statement.execute(params, callback);
		} finally {
			statement.close();
		}
	}

	Row first(String statement, [params = const []]) {
		var result = null;
		execute(statement, params, (row) {
			result = row;
			return true;
		});
		return result;
	}

	_checkOpen() {
		if (_db == null) throw new Exception("Database is closed");
	}
}

class Statement {
	final _db;
	var _statement;
	final String sql;

	Statement._internal(db, sql) : _db = db, this.sql = sql {
		_statement = _prepare(_db, sql);
	}

	void close() => _closeStatement(_db, _statement);

	int execute([params = const [], bool callback(Row)]) {
		_reset(_db, _statement);
		if (params.length > 0) _bind(_db, _statement, params);
		var result;
		int count = 0;
		var info = null;
		while ((result = _step(_db, _statement)) is! int) {
			count++;
			if (info == null) info = _column_info(_db, _statement);
			if (callback != null && callback(new Row._internal(info, result))) break;
		}
		// If update affected no rows, count == result == 0
		return (result == 0) ? count : result;
	}
}

class Row {
	final List<String> _columns;
	final List _data;

	Row._internal(this._columns, this._data);

	operator [](i) => _data[i];
	toString() => _data.toString();

	noSuchMethod(String method, List args) {
		if (args.length == 0 && method.startsWith("get:")) {
			int i = _columns.indexOf(method.substring(4));
			if (i >= 0) return _data[i];
		}
		return super.noSuchMethod(method, args);
	}
}

_prepare(db, query) native 'PrepareStatement';
_reset(db, statement) native 'Reset';
_bind(db, statement, params) native 'Bind';
_column_info(db, statement) native 'ColumnInfo';
_step(db, statement) native 'Step';
_closeStatement(db, statement) native 'CloseStatement';

_new(path) native 'New';
_close(handle) native 'Close';
_version() native 'Version';
_raiseError(message) {
	throw new Exception(message);
}
