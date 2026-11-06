#!/usr/bin/env python3
"""Generate JDK-only records and TypeScript types from the shared product schema."""
from pathlib import Path
import json,sys,argparse,re
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['generate','check'],nargs='?',default='generate')
args=parser.parse_args()
root=Path(__file__).resolve().parent
schema=json.loads((root/'coding-presentation.schema.json').read_text())

def java_type(s):
    if '$ref' in s: return s['$ref'].split('/')[-1]
    if 'anyOf' in s:
        t=java_type(s['anyOf'][0]);return {'long':'Long','boolean':'Boolean'}.get(t,t)
    if s['type']=='array': return 'List<'+java_type(s['items'])+'>'
    return {'string':'String','integer':'long','boolean':'boolean'}[s['type']]
def ts_type(s):
    if '$ref' in s:return s['$ref'].split('/')[-1]
    if 'anyOf' in s:return ' | '.join(ts_type(x) for x in s['anyOf'])
    if 'const' in s:return json.dumps(s['const'])
    if 'enum' in s:return ' | '.join(json.dumps(x) for x in s['enum'])
    if s['type']=='array':return 'ReadonlyArray<'+ts_type(s['items'])+'>'
    return {'string':'string','integer':'number','boolean':'boolean','null':'null'}[s['type']]
j=['package io.haifa.coding.api;','', 'import java.util.List;','import java.util.Objects;','', '/** Generated from contract/coding-presentation.schema.json. No engine or Runtime dependencies. */','public final class CodingProtocol {','    private CodingProtocol() {}']
t=['// Generated from coding-presentation.schema.json; framework/runtime independent.']
for name,spec in schema['$defs'].items():
    props=spec['properties'];fields=', '.join(java_type(v)+' '+k for k,v in props.items())
    j+=['    public record '+name+'('+fields+')'+(' implements Envelope' if name in ['Snapshot','Update','Resync'] else '')+' {','        public '+name+' {']
    t+=['export type '+name+' = Readonly<{']
    for k,v in props.items():
        t+=['  '+k+('' if k in spec['required'] else '?')+': '+ts_type(v)+';']
        optional='anyOf' in v
        actual=v['anyOf'][0] if optional else v
        if optional:j+=['            if ('+k+' != null) {']
        prefix='                ' if optional else '            '
        typ=java_type(actual)
        if typ=='String':
            j+=[prefix+'Objects.requireNonNull('+k+', "'+k+'");']
            if 'minLength' in actual:j+=[prefix+'if ('+k+'.isEmpty()) throw new IllegalArgumentException("Empty '+k+'");']
            if 'maxLength' in actual:j+=[prefix+'if ('+k+'.codePointCount(0, '+k+'.length()) > '+str(actual['maxLength'])+') throw new IllegalArgumentException("'+k+' exceeds budget");']
            if 'enum' in actual:j+=[prefix+'if (!java.util.Set.of('+', '.join(json.dumps(x) for x in actual['enum'])+').contains('+k+')) throw new IllegalArgumentException("Unknown '+k+'");']
        elif typ=='long': j+=[prefix+'if ('+k+' < '+str(actual.get('minimum',0))+'L || '+k+' > '+str(actual.get('maximum',9007199254740991))+'L) throw new IllegalArgumentException("Invalid '+k+'");']
        elif typ.startswith('List<'):
            j+=[prefix+k+' = List.copyOf('+k+');',prefix+'if ('+k+'.size() > '+str(actual['maxItems'])+') throw new IllegalArgumentException("'+k+' exceeds budget");']
            if actual['items'].get('type') == 'string' and 'maxLength' in actual['items']:
                j += [prefix+'if ('+k+'.stream().anyMatch(item -> item.codePointCount(0, item.length()) > '+str(actual['items']['maxLength'])+')) throw new IllegalArgumentException("'+k+' item exceeds budget");']
        elif typ!='boolean':j+=[prefix+'Objects.requireNonNull('+k+', "'+k+'");']
        if 'const' in actual:
            test=(k+'.equals('+json.dumps(actual['const'])+')') if typ=='String' else k+' == '+str(actual['const'])
            j+=[prefix+'if (!('+test+')) throw new IllegalArgumentException("Invalid '+k+'");']
        if optional:j+=['            }']
    j+=['        }']
    if name == 'Snapshot':
        previous_fields = ', '.join(java_type(v)+' '+k for k,v in props.items() if k != 'activity')
        previous_args = ', '.join(k for k in props if k != 'activity')
        j += ['        public Snapshot('+previous_fields+') { this('+previous_args+', null); }',
              '        public Snapshot withActivity(ActivityHead head) { return new Snapshot('+previous_args+', head); }']
    if any(prop.get('$comment', '').startswith('Sensitive proof;') for prop in props.values()):
        j += ['        @Override public String toString() { return "'+name+'[redacted]"; }']
    j+=['    }'];t+=['}>;']
j+=['    public sealed interface Envelope permits Snapshot, Update, Resync {}','}'];t+=['export type CodingEnvelope = Snapshot | Update | Resync;']
outputs={root.parent/'src/main/java/io/haifa/coding/api/CodingProtocol.java':'\n'.join(j)+'\n',root/'generated.ts':'\n'.join(t)+'\n'}
def java_tokens(source):
    # Ignore formatter whitespace, while preserving literal contents and token boundaries.
    return re.findall(r'"(?:\\.|[^"\\])*"|[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|[^\s]', source)
for path,contents in outputs.items():
    if args.action=='check':
        if not path.exists() or (java_tokens(path.read_text()) != java_tokens(contents) if path.suffix=='.java' else path.read_text()!=contents): raise SystemExit('Stale generated contract: '+str(path))
    else:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(contents)
