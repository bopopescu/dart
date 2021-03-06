#!/usr/bin/python
# Copyright (c) 2012, the Dart project authors.  Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

"""This module provides shared functionality for systems to generate
Dart APIs from the IDL database."""

import re

_pure_interfaces = set([
    'DOMStringList',
    'DOMStringMap',
    'ElementTimeControl',
    'ElementTraversal',
    'MediaQueryListListener',
    'NodeSelector',
    'SVGExternalResourcesRequired',
    'SVGFilterPrimitiveStandardAttributes',
    'SVGFitToViewBox',
    'SVGLangSpace',
    'SVGLocatable',
    'SVGStylable',
    'SVGTests',
    'SVGTransformable',
    'SVGURIReference',
    'SVGViewSpec',
    'SVGZoomAndPan',
    'TimeoutHandler'])

def IsPureInterface(interface_name):
  return interface_name in _pure_interfaces

#
# Renames for attributes that have names that are not legal Dart names.
#
_dart_attribute_renames = {
    'default': 'defaultValue',
    'final': 'finalValue',
}

#
# Interface version of the DOM needs to delegate typed array constructors to a
# factory provider.
#
interface_factories = {
    'Float32Array': '_TypedArrayFactoryProvider',
    'Float64Array': '_TypedArrayFactoryProvider',
    'Int8Array': '_TypedArrayFactoryProvider',
    'Int16Array': '_TypedArrayFactoryProvider',
    'Int32Array': '_TypedArrayFactoryProvider',
    'Uint8Array': '_TypedArrayFactoryProvider',
    'Uint16Array': '_TypedArrayFactoryProvider',
    'Uint32Array': '_TypedArrayFactoryProvider',
    'Uint8ClampedArray': '_TypedArrayFactoryProvider',
}

#
# Custom native specs for the Frog dom.
#
_frog_dom_custom_native_specs = {
    # Decorate the singleton Console object, if present (workers do not have a
    # console).
    'Console': "=(typeof console == 'undefined' ? {} : console)",

    # DOMWindow aliased with global scope.
    'DOMWindow': '@*DOMWindow',
}

#
# Custom native bodies for frog implementations of dom operations that appear in
# dart:dom and dart:html.  This is used to work-around the lack of a 'rename'
# feature in the 'native' string - the correct name is available on the DartName
# extended attribute. See Issue 1814
#
dom_frog_native_bodies = {
    # Some JavaScript processors, especially tools like yuicompress and
    # JSCompiler, choke on 'this.continue'
    'IDBCursor.continueFunction':
      """
        if (key == null) return this['continue']();
        return this['continue'](key);
      """,
}

def IsPrimitiveType(type_name):
  return isinstance(GetIDLTypeInfo(type_name), PrimitiveIDLTypeInfo)

def MaybeListElementTypeName(type_name):
  """Returns the List element type T from string of form "List<T>", or None."""
  match = re.match(r'sequence<(\w*)>$', type_name)
  if match:
    return match.group(1)
  return None

def MaybeListElementType(interface):
  """Returns the List element type T, or None in interface does not implement
  List<T>.
  """
  for parent in interface.parents:
    element_type = MaybeListElementTypeName(parent.type.id)
    if element_type:
      return element_type
  return None

def MaybeTypedArrayElementType(interface):
  """Returns the typed array element type, or None in interface is not a
  TypedArray.
  """
  # Typed arrays implement ArrayBufferView and List<T>.
  for parent in interface.parents:
    if  parent.type.id == 'ArrayBufferView':
      return MaybeListElementType(interface)
    if  parent.type.id == 'Uint8Array':
      return 'int'
  return None

def MakeNativeSpec(javascript_binding_name):
  if javascript_binding_name in _frog_dom_custom_native_specs:
    return _frog_dom_custom_native_specs[javascript_binding_name]
  else:
    # Make the class 'hidden' so it is dynamically patched at runtime.  This
    # is useful not only for browser compat, but to allow code that links
    # against dart:dom to load in a worker isolate.
    return '*' + javascript_binding_name


def MatchSourceFilter(filter, thing):
  if not filter:
    return True
  else:
    return any(token in thing.annotations for token in filter)


def DartType(idl_type_name):
  return GetIDLTypeInfo(idl_type_name).dart_type()


class ParamInfo(object):
  """Holder for various information about a parameter of a Dart operation.

  Attributes:
    name: Name of parameter.
    type_id: Original type id.  None for merged types.
    dart_type: DartType of parameter.
    default_value: String holding the expression.  None for mandatory parameter.
  """
  def __init__(self, name, type_id, dart_type, default_value):
    self.name = name
    self.type_id = type_id
    self.dart_type = dart_type
    self.default_value = default_value


# Given a list of overloaded arguments, render a dart argument.
def _DartArg(args, interface):
  # Given a list of overloaded arguments, choose a suitable name.
  def OverloadedName(args):
    return '_OR_'.join(sorted(set(arg.id for arg in args)))

  # Given a list of overloaded arguments, choose a suitable type.
  def OverloadedType(args):
    type_ids = sorted(set(arg.type.id for arg in args))
    dart_types = sorted(set(DartType(arg.type.id) for arg in args))
    if len(dart_types) == 1:
      if len(type_ids) == 1:
        return (type_ids[0], dart_types[0])
      else:
        return (None, dart_types[0])
    else:
      return (None, TypeName(type_ids, interface))

  filtered = filter(None, args)
  optional = any(not arg or arg.is_optional for arg in args)
  (type_id, dart_type) = OverloadedType(filtered)
  name = OverloadedName(filtered)
  if optional:
    return ParamInfo(name, type_id, dart_type, 'null')
  else:
    return ParamInfo(name, type_id, dart_type, None)


def AnalyzeOperation(interface, operations):
  """Makes operation calling convention decision for a set of overloads.

  Returns: An OperationInfo object.
  """

  # Zip together arguments from each overload by position, then convert
  # to a dart argument.
  args = map(lambda *args: _DartArg(args, interface),
             *(op.arguments for op in operations))

  info = OperationInfo()
  info.overloads = operations
  info.declared_name = operations[0].id
  info.name = operations[0].ext_attrs.get('DartName', info.declared_name)
  info.js_name = info.declared_name
  info.type_name = DartType(operations[0].type.id)   # TODO: widen.
  info.param_infos = args
  return info


def AnalyzeConstructor(interface):
  """Returns an OperationInfo object for the constructor.

  Returns None if the interface has no Constructor.
  """
  def GetArgs(func_value):
    return map(lambda arg: _DartArg([arg], interface), func_value.arguments)

  if 'Constructor' in interface.ext_attrs:
    name = None
    func_value = interface.ext_attrs.get('Constructor')
    if func_value:
      # [Constructor(param,...)]
      args = GetArgs(func_value)
      idl_args = func_value.arguments
    else: # [Constructor]
      args = []
      idl_args = []
  else:
    func_value = interface.ext_attrs.get('NamedConstructor')
    if func_value:
      name = func_value.id
      args = GetArgs(func_value)
      idl_args = func_value.arguments
    else:
      return None

  info = OperationInfo()
  info.overloads = None
  info.idl_args = idl_args
  info.declared_name = name
  info.name = name
  info.js_name = name
  info.type_name = interface.id
  info.param_infos = args
  return info


def RecognizeCallback(interface):
  """Returns the info for the callback method if the interface smells like a
  callback.
  """
  if 'Callback' not in interface.ext_attrs: return None
  handlers = [op for op in interface.operations if op.id == 'handleEvent']
  if not handlers: return None
  if not (handlers == interface.operations): return None
  return AnalyzeOperation(interface, handlers)

def IsDartListType(type):
  return type == 'List' or type.startswith('sequence<')

def IsDartCollectionType(type):
  return IsDartListType(type)

def FindMatchingAttribute(interface, attr1):
  matches = [attr2 for attr2 in interface.attributes
             if attr1.id == attr2.id
             and attr1.is_fc_getter == attr2.is_fc_getter
             and attr1.is_fc_setter == attr2.is_fc_setter]
  if matches:
    assert len(matches) == 1
    return matches[0]
  return None


def DartDomNameOfAttribute(attr):
  """Returns the Dart name for an IDLAttribute.

  attr.id is the 'native' or JavaScript name.

  To ensure uniformity, work with the true IDL name until as late a possible,
  e.g. translate to the Dart name when generating Dart code.
  """
  name = attr.id
  name = _dart_attribute_renames.get(name, name)
  name = attr.ext_attrs.get('DartName', None) or name
  return name


def TypeOrNothing(dart_type, comment=None):
  """Returns string for declaring something with |dart_type| in a context
  where a type may be omitted.
  The string is empty or has a trailing space.
  """
  if dart_type == 'Dynamic':
    if comment:
      return '/*%s*/ ' % comment   # Just a comment foo(/*T*/ x)
    else:
      return ''                    # foo(x) looks nicer than foo(Dynamic x)
  else:
    return dart_type + ' '


def TypeOrVar(dart_type, comment=None):
  """Returns string for declaring something with |dart_type| in a context
  where if a type is omitted, 'var' must be used instead."""
  if dart_type == 'Dynamic':
    if comment:
      return 'var /*%s*/' % comment   # e.g.  var /*T*/ x;
    else:
      return 'var'                    # e.g.  var x;
  else:
    return dart_type


class OperationInfo(object):
  """Holder for various derived information from a set of overloaded operations.

  Attributes:
    overloads: A list of IDL operation overloads with the same name.
    name: A string, the simple name of the operation.
    type_name: A string, the name of the return type of the operation.
    param_infos: A list of ParamInfo.
  """

  def ParametersInterfaceDeclaration(self):
    """Returns a formatted string declaring the parameters for the interface."""
    return self._FormatParams(
        self.param_infos, True,
        lambda param: TypeOrNothing(param.dart_type, param.type_id))

  def ParametersImplementationDeclaration(self, rename_type=None):
    """Returns a formatted string declaring the parameters for the
    implementation.

    Args:
      rename_type: A function that allows the types to be renamed.
        The function is applied to the parameter's dart_type.
    """
    if rename_type:
      def renamer(param_info):
        return TypeOrNothing(rename_type(param_info.dart_type))
      return self._FormatParams(self.param_infos, False, renamer)
    else:
      def type_fn(param_info):
        if param_info.dart_type == 'Dynamic':
          if param_info.type_id:
            # It is more informative to use a comment IDL type.
            return '/*%s*/' % param_info.type_id
          else:
            return 'var'
        else:
          return param_info.dart_type
      return self._FormatParams(
          self.param_infos, False,
          lambda param: TypeOrNothing(param.dart_type, param.type_id))

  def ParametersAsArgumentList(self):
    """Returns a string of the parameter names suitable for passing the
    parameters as arguments.
    """
    return ', '.join(map(lambda param_info: param_info.name, self.param_infos))

  def _FormatParams(self, params, is_interface, type_fn):
    def FormatParam(param):
      """Returns a parameter declaration fragment for an ParamInfo."""
      type = type_fn(param)
      if is_interface or param.default_value is None:
        return '%s%s' % (type, param.name)
      else:
        return '%s%s = %s' % (type, param.name, param.default_value)

    required = []
    optional = []
    for param_info in params:
      if param_info.default_value:
        optional.append(param_info)
      else:
        if optional:
          raise Exception('Optional parameters cannot precede required ones: '
                          + str(args))
        required.append(param_info)
    argtexts = map(FormatParam, required)
    if optional:
      argtexts.append('[' + ', '.join(map(FormatParam, optional)) + ']')
    return ', '.join(argtexts)


def AttributeOutputOrder(a, b):
  """Canonical output ordering for attributes."""
    # Getters before setters:
  if a.id < b.id: return -1
  if a.id > b.id: return 1
  if a.is_fc_setter < b.is_fc_setter: return -1
  if a.is_fc_setter > b.is_fc_setter: return 1
  return 0

def ConstantOutputOrder(a, b):
  """Canonical output ordering for constants."""
  if a.id < b.id: return -1
  if a.id > b.id: return 1
  return 0


def _FormatNameList(names):
  """Returns JavaScript array literal expression with one name per line."""
  #names = sorted(names)
  if len(names) <= 1:
    expression_string = str(names)  # e.g.  ['length']
  else:
    expression_string = ',\n   '.join(str(names).split(','))
    expression_string = expression_string.replace('[', '[\n    ')
  return expression_string


def IndentText(text, indent):
  """Format lines of text with indent."""
  def FormatLine(line):
    if line.strip():
      return '%s%s\n' % (indent, line)
    else:
      return '\n'
  return ''.join(FormatLine(line) for line in text.split('\n'))

# Given a sorted sequence of type identifiers, return an appropriate type
# name
def TypeName(type_ids, interface):
  # Dynamically type this field for now.
  return 'Dynamic'

# ------------------------------------------------------------------------------

class IDLTypeInfo(object):
  def __init__(self, idl_type, dart_type=None,
               native_type=None, ref_counted=True,
               has_dart_wrapper=True,
               custom_to_dart=False, conversion_includes=[]):
    self._idl_type = idl_type
    self._dart_type = dart_type
    self._native_type = native_type
    self._ref_counted = ref_counted
    self._has_dart_wrapper = has_dart_wrapper
    self._custom_to_dart = custom_to_dart
    self._conversion_includes = conversion_includes + [idl_type]

  def idl_type(self):
    return self._idl_type

  def dart_type(self):
    return self._dart_type or self._idl_type

  def native_type(self):
    return self._native_type or self._idl_type

  def parameter_adapter_info(self):
    native_type = self.native_type()
    if self._ref_counted:
      native_type = 'RefPtr< %s >' % native_type
    if self._has_dart_wrapper:
      wrapper_type = 'Dart%s' % self.idl_type()
      adapter_type = 'ParameterAdapter<%s, %s>' % (native_type, wrapper_type)
      return (adapter_type, '"%s.h"' % wrapper_type)
    return ('ParameterAdapter< %s >' % native_type, '"%s.h"' % self._idl_type)

  def parameter_type(self):
    return '%s*' % self.native_type()

  def webcore_includes(self):
    WTF_INCLUDES = [
        'ArrayBuffer',
        'ArrayBufferView',
        'Float32Array',
        'Float64Array',
        'Int8Array',
        'Int16Array',
        'Int32Array',
        'Uint8Array',
        'Uint16Array',
        'Uint32Array',
        'Uint8ClampedArray',
    ]

    if self._idl_type in WTF_INCLUDES:
      return ['<wtf/%s.h>' % self._idl_type]

    if not self._idl_type.startswith('SVG'):
      return ['"%s.h"' % self._idl_type]

    if self._idl_type in ['SVGNumber', 'SVGPoint']:
      return []
    if self._idl_type.startswith('SVGPathSeg'):
      include = self._idl_type.replace('Abs', '').replace('Rel', '')
    else:
      include = self._idl_type
    return ['"%s.h"' % include] + _svg_supplemental_includes

  def receiver(self):
    return 'receiver->'

  def conversion_includes(self):
    return ['"Dart%s.h"' % include for include in self._conversion_includes]

  def to_dart_conversion(self, value, interface_name, attributes):
    return 'toDartValue(%s)' % value

  def custom_to_dart(self):
    return self._custom_to_dart


class SequenceIDLTypeInfo(IDLTypeInfo):
  def __init__(self, idl_type, item_info):
    super(SequenceIDLTypeInfo, self).__init__(idl_type)
    self._item_info = item_info

  def dart_type(self):
    return 'List<%s>' % self._item_info.dart_type()

  def conversion_includes(self):
    return self._item_info.conversion_includes()


class PrimitiveIDLTypeInfo(IDLTypeInfo):
  def __init__(self, idl_type, dart_type, native_type=None, ref_counted=False,
               needs_static_cast=False,
               webcore_getter_name='getAttribute',
               webcore_setter_name='setAttribute'):
    super(PrimitiveIDLTypeInfo, self).__init__(idl_type, dart_type=dart_type,
        native_type=native_type, ref_counted=ref_counted)
    self._needs_static_cast = needs_static_cast
    self._webcore_getter_name = webcore_getter_name
    self._webcore_setter_name = webcore_setter_name

  def parameter_adapter_info(self):
    native_type = self.native_type()
    if self._ref_counted:
      native_type = 'RefPtr< %s >' % native_type
    return ('ParameterAdapter< %s >' % native_type, None)

  def parameter_type(self):
    if self.native_type() == 'String':
      return 'const String&'
    return self.native_type()

  def conversion_includes(self):
    return []

  def to_dart_conversion(self, value, interface_name, attributes):
    if self._needs_static_cast:
      value = 'static_cast<%s>(%s)' % (self.native_type(), value)
    conversion_arguments = [value]
    if 'TreatReturnedNullStringAs' in attributes:
        conversion_arguments.append('ConvertDefaultToNull')
    return 'toDartValue(%s)' % ', '.join(conversion_arguments)

  def webcore_getter_name(self):
    return self._webcore_getter_name

  def webcore_setter_name(self):
    return self._webcore_setter_name

class SVGTearOffIDLTypeInfo(IDLTypeInfo):
  def __init__(self, idl_type, native_type='', ref_counted=True):
    super(SVGTearOffIDLTypeInfo, self).__init__(idl_type,
                                                native_type=native_type,
                                                ref_counted=ref_counted)

  def native_type(self):
    if self._native_type:
      return self._native_type
    tear_off_type = 'SVGPropertyTearOff'
    if self._idl_type.endswith('List'):
      tear_off_type = 'SVGListPropertyTearOff'
    return '%s<%s>' % (tear_off_type, self._idl_type)

  def receiver(self):
    if self._idl_type.endswith('List'):
      return 'receiver->'
    return 'receiver->propertyReference().'

  def to_dart_conversion(self, value, interface_name, attributes):
    svg_primitive_types = ['SVGAngle', 'SVGLength', 'SVGMatrix',
        'SVGNumber', 'SVGPoint', 'SVGRect', 'SVGTransform']
    conversion_cast = '%s::create(%s)'
    if interface_name.startswith('SVGAnimated'):
      conversion_cast = 'static_cast<%s*>(%s)'
    elif self.idl_type() == 'SVGStringList':
      conversion_cast = '%s::create(receiver, %s)'
    elif interface_name.endswith('List'):
      conversion_cast = 'static_cast<%s*>(%s.get())'
    elif self.idl_type() in svg_primitive_types:
      conversion_cast = '%s::create(%s)'
    else:
      conversion_cast = 'static_cast<%s*>(%s)'
    conversion_cast = conversion_cast % (self.native_type(), value)
    return 'toDartValue(%s)' %  conversion_cast

_idl_type_registry = {
    # There is GC3Dboolean which is not a bool, but unsigned char for OpenGL compatibility.
    'boolean': PrimitiveIDLTypeInfo('boolean', dart_type='bool', native_type='bool',
                                    needs_static_cast=True,
                                    webcore_getter_name='hasAttribute',
                                    webcore_setter_name='setBooleanAttribute'),
    # Some IDL's unsigned shorts/shorts are mapped to WebCore C++ enums, so we
    # use a static_cast<int> here not to provide overloads for all enums.
    'short': PrimitiveIDLTypeInfo('short', dart_type='int', native_type='int',
        needs_static_cast=True),
    'unsigned short': PrimitiveIDLTypeInfo('unsigned short', dart_type='int',
        native_type='int', needs_static_cast=True,),
    'int': PrimitiveIDLTypeInfo('int', dart_type='int'),
    'unsigned int': PrimitiveIDLTypeInfo('unsigned int', dart_type='int',
        native_type='unsigned'),
    'long': PrimitiveIDLTypeInfo('long', dart_type='int', native_type='int',
        webcore_getter_name='getIntegralAttribute',
        webcore_setter_name='setIntegralAttribute'),
    'unsigned long': PrimitiveIDLTypeInfo('unsigned long', dart_type='int',
        native_type='unsigned',
        webcore_getter_name='getUnsignedIntegralAttribute',
        webcore_setter_name='setUnsignedIntegralAttribute'),
    'long long': PrimitiveIDLTypeInfo('long long', dart_type='int'),
    'unsigned long long': PrimitiveIDLTypeInfo('unsigned long long', dart_type='int'),
    'float': PrimitiveIDLTypeInfo('float', dart_type='num', native_type='double'),
    'double': PrimitiveIDLTypeInfo('double', dart_type='num'),

    'any': PrimitiveIDLTypeInfo('any', dart_type='Object'),
    'any[]': PrimitiveIDLTypeInfo('any[]', dart_type='List'),
    'Array': PrimitiveIDLTypeInfo('Array', dart_type='List'),
    'custom': PrimitiveIDLTypeInfo('custom', dart_type='Dynamic'),
    'Date': PrimitiveIDLTypeInfo('Date', dart_type='Date', native_type='double'),
    'DOMObject': PrimitiveIDLTypeInfo('DOMObject', dart_type='Object', native_type='ScriptValue'),
    'DOMString': PrimitiveIDLTypeInfo('DOMString', dart_type='String', native_type='String'),
    # TODO(sra): Flags is really a dictionary: {create:bool, exclusive:bool}
    # http://dev.w3.org/2009/dap/file-system/file-dir-sys.html#the-flags-interface
    'Flags': PrimitiveIDLTypeInfo('Flags', dart_type='Object'),
    'DOMTimeStamp': PrimitiveIDLTypeInfo('DOMTimeStamp', dart_type='int'),
    'object': PrimitiveIDLTypeInfo('object', dart_type='Object', native_type='ScriptValue'),
    # TODO(sra): Come up with some meaningful name so that where this appears in
    # the documentation, the user is made aware that only a limited subset of
    # serializable types are actually permitted.
    'SerializedScriptValue': PrimitiveIDLTypeInfo('SerializedScriptValue', dart_type='Dynamic', ref_counted=True),
    # TODO(sra): Flags is really a dictionary: {create:bool, exclusive:bool}
    # http://dev.w3.org/2009/dap/file-system/file-dir-sys.html#the-flags-interface
    'WebKitFlags': PrimitiveIDLTypeInfo('WebKitFlags', dart_type='Object'),

    'DOMStringList': PrimitiveIDLTypeInfo('DOMStringList', dart_type='List<String>'),
    'sequence': PrimitiveIDLTypeInfo('sequence', dart_type='List'),
    'void': PrimitiveIDLTypeInfo('void', dart_type='void'),

    'CSSRule': IDLTypeInfo('CSSRule', conversion_includes=['CSSImportRule']),
    'DOMException': IDLTypeInfo('DOMCoreException', dart_type='DOMException'),
    'DOMStringMap': IDLTypeInfo('DOMStringMap', dart_type='Map<String, String>'),
    'DOMWindow': IDLTypeInfo('DOMWindow', custom_to_dart=True),
    'Element': IDLTypeInfo('Element', custom_to_dart=True),
    'EventListener': IDLTypeInfo('EventListener', has_dart_wrapper=False),
    'EventTarget': IDLTypeInfo('EventTarget', has_dart_wrapper=False),
    'HTMLElement': IDLTypeInfo('HTMLElement', custom_to_dart=True),
    'IDBAny': IDLTypeInfo('IDBAny', dart_type='Dynamic', has_dart_wrapper=False),
    'IDBKey': IDLTypeInfo('IDBKey', dart_type='Dynamic', has_dart_wrapper=False),
    'MediaQueryListListener': IDLTypeInfo('MediaQueryListListener', has_dart_wrapper=False),
    'OptionsObject': IDLTypeInfo('OptionsObject', has_dart_wrapper=False),
    'StyleSheet': IDLTypeInfo('StyleSheet', conversion_includes=['CSSStyleSheet']),
    'SVGElement': IDLTypeInfo('SVGElement', custom_to_dart=True),

    'SVGAngle': SVGTearOffIDLTypeInfo('SVGAngle'),
    'SVGLength': SVGTearOffIDLTypeInfo('SVGLength'),
    'SVGLengthList': SVGTearOffIDLTypeInfo('SVGLengthList', ref_counted=False),
    'SVGMatrix': SVGTearOffIDLTypeInfo('SVGMatrix'),
    'SVGNumber': SVGTearOffIDLTypeInfo('SVGNumber', native_type='SVGPropertyTearOff<float>'),
    'SVGNumberList': SVGTearOffIDLTypeInfo('SVGNumberList', ref_counted=False),
    'SVGPathSegList': SVGTearOffIDLTypeInfo('SVGPathSegList', native_type='SVGPathSegListPropertyTearOff', ref_counted=False),
    'SVGPoint': SVGTearOffIDLTypeInfo('SVGPoint', native_type='SVGPropertyTearOff<FloatPoint>'),
    'SVGPointList': SVGTearOffIDLTypeInfo('SVGPointList', ref_counted=False),
    'SVGPreserveAspectRatio': SVGTearOffIDLTypeInfo('SVGPreserveAspectRatio'),
    'SVGRect': SVGTearOffIDLTypeInfo('SVGRect', native_type='SVGPropertyTearOff<FloatRect>'),
    'SVGStringList': SVGTearOffIDLTypeInfo('SVGStringList', native_type='SVGStaticListPropertyTearOff<SVGStringList>', ref_counted=False),
    'SVGTransform': SVGTearOffIDLTypeInfo('SVGTransform'),
    'SVGTransformList': SVGTearOffIDLTypeInfo('SVGTransformList', native_type='SVGTransformListPropertyTearOff', ref_counted=False)
}

_svg_supplemental_includes = [
    '"SVGAnimatedPropertyTearOff.h"',
    '"SVGAnimatedListPropertyTearOff.h"',
    '"SVGStaticListPropertyTearOff.h"',
    '"SVGAnimatedListPropertyTearOff.h"',
    '"SVGTransformListPropertyTearOff.h"',
    '"SVGPathSegListPropertyTearOff.h"',
]

def GetIDLTypeInfo(idl_type_name):
  match = re.match(r'sequence<(\w+)>$', idl_type_name)
  if match:
    return SequenceIDLTypeInfo(idl_type_name, GetIDLTypeInfo(match.group(1)))
  return _idl_type_registry.get(idl_type_name, IDLTypeInfo(idl_type_name))
