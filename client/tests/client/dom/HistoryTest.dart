#library('HistoryTest');
#import('../../../../lib/unittest/unittest.dart');
#import('../../../../lib/unittest/dom_config.dart');
#import('dart:dom');

main() {
  useDomConfiguration();
  test('History', () {
    window.history.pushState(null, document.title, '?foo=bar');
    expect(window.history.length).equals(2);
    window.history.back();
    expect(window.location.href.endsWith('foo=bar')).isTrue();

    window.history.replaceState(null, document.title, '?foo=baz');
    Expect.equals(2, window.history.length);
    expect(window.location.href.endsWith('foo=baz')).isTrue();
  });
}
