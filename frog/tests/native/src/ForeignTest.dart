// Copyright (c) 2011, the Dart project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

foreign1(var a, var b) {
  return JS("num", @"# + #", a, b);
}

var called = false;
callOnce() {
  Expect.isFalse(called);
  called = true;
  return 499;
}

foreign2() {
  var t = callOnce();
  return JS("num", @"# + #", t, t);
}

foreign11(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11) {
  return JS("num", @"# + # + # + # + # + # + # + # + # + # + #",
      a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
}

void main() {
  Expect.equals(9, foreign1(4, 5));
  Expect.equals(998, foreign2());
  Expect.equals('1234567891011',
      foreign11('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11'));
}
