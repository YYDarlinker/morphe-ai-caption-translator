package app.yydarlinker.deepseekcaptions;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Parses common YouTube Timed Text formats and exposes a format-independent cue timeline. */
final class CaptionDocument {
    interface Parsed {
        List<String> texts();
        List<Cue> cues();
        default JSONObject json3() { return null; }
        default WebVttSourceReader.Result vtt() { return null; }
        default String detectedFormat() { return "unknown"; }
        default List<RawFragment> rawFragments() { return Collections.emptyList(); }
        default String timingCapabilities() {
            int offsets=0,speakers=0;for(RawFragment fragment:rawFragments()){
                if(fragment.nativeOffset)offsets++;speakers+=fragment.speakerOffsets.size();
            }
            if(vtt()!=null)for(WebVttSourceReader.Range range:vtt().ranges){if(range.nativeStart)offsets++;speakers+=range.speakerMarkers.size();}
            return "detected_fmt="+detectedFormat()+";raw_fragments="+rawFragments().size()+";offset_segments="+offsets+";speaker_markers="+speakers;
        }
        byte[] render(List<String> translated) throws Exception;
        String contentType();
    }

    static final class RawFragment {
        final int id,cue;
        final String raw;
        final long startMs,endMs;
        final boolean nativeOffset;
        final List<Integer> speakerOffsets;
        RawFragment(int id,int cue,String raw,long start,long end,boolean nativeOffset,List<Integer> speakers){
            this.id=id;this.cue=cue;this.raw=raw;startMs=start;endMs=end;this.nativeOffset=nativeOffset;
            speakerOffsets=Collections.unmodifiableList(new ArrayList<>(speakers));
        }
    }

    static final class Cue {
        final long startMs;
        final long endMs;
        final String text;
        final int authorOrdinal;
        final String rawText;
        final List<RawFragment> rawFragments;
        final List<Integer> explicitLineBreaks;
        final String sourceKind,precision;

        Cue(long startMs, long endMs, String text) {
            this(startMs,endMs,text,-1,Collections.emptyList());
        }
        Cue(long startMs,long endMs,String text,int ordinal,List<RawFragment> fragments){
            this(startMs,endMs,text,ordinal,fragments,"unknown");
        }
        Cue(long startMs,long endMs,String text,int ordinal,List<RawFragment> fragments,String kind){
            sourceKind=kind;boolean offsets=false;for(RawFragment fragment:fragments)offsets|=fragment.nativeOffset;precision=offsets?"native_segment":"author_cue";
            authorOrdinal=ordinal;rawText=text;rawFragments=Collections.unmodifiableList(new ArrayList<>(fragments));
            List<Integer> breaks=new ArrayList<>();for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')breaks.add(i);explicitLineBreaks=Collections.unmodifiableList(breaks);
            this.startMs = Math.max(0L, startMs);
            this.endMs = Math.max(this.startMs + 1L, endMs);
            this.text = text;
        }
        Cue withSourceKind(String kind){return new Cue(startMs,endMs,rawText,authorOrdinal,rawFragments,kind);}
    }

    private CaptionDocument() {}

    static Parsed parse(byte[] data, String fallbackContentType) throws Exception {
        if (data == null || data.length == 0) throw new IllegalArgumentException("字幕为空");
        String body = new String(data, StandardCharsets.UTF_8);
        if(body.startsWith("\uFEFF"))body=body.substring(1);
        String trimmed = body.trim();

        if (trimmed.startsWith("{") && trimmed.contains("\"events\"")) {
            return parseJson3(body);
        }
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<transcript") ||
                trimmed.startsWith("<timedtext")) {
            return parseXml(data);
        }
        if (trimmed.startsWith("WEBVTT")) return parseBlockFormat(body, "text/vtt; charset=utf-8");
        if (looksLikeSrt(trimmed)) {
            return parseBlockFormat(body, "application/x-subrip; charset=utf-8");
        }

        throw new IllegalArgumentException("暂不支持此字幕格式: " + safeType(fallbackContentType));
    }

    private static Parsed parseJson3(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray events = root.optJSONArray("events");
        if (events == null) throw new IllegalArgumentException("JSON3 缺少 events");

        List<JSONObject> cueEvents = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Cue> rawCues = new ArrayList<>();
        List<RawFragment> fragments=new ArrayList<>();
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            JSONArray segments = event.optJSONArray("segs");
            if (segments == null || segments.length() == 0) continue;

            long startMs = event.optLong("tStartMs", -1L);
            if (startMs < 0L) continue;
            long durationMs = event.optLong("dDurationMs", -1L);

            StringBuilder text = new StringBuilder();
            for (int j = 0; j < segments.length(); j++) {
                JSONObject segment = segments.optJSONObject(j);
                if (segment != null) {
                    String exact=segment.optString("utf8", "");text.append(exact);
                    long offset=segment.optLong("tOffsetMs",0),until=durationMs>0?durationMs:2000;
                    for(int k=j+1;k<segments.length();k++){JSONObject next=segments.optJSONObject(k);if(next!=null&&next.has("tOffsetMs")){until=next.optLong("tOffsetMs",until);break;}}
                    fragments.add(new RawFragment(fragments.size(),i,exact,startMs+offset,startMs+until,segment.has("tOffsetMs"),Collections.emptyList()));
                }
            }
            String value = text.toString();
            if (value.trim().isEmpty()) continue;

            cueEvents.add(event);
            texts.add(value);
            rawCues.add(new Cue(startMs, durationMs > 0L ? startMs + durationMs : startMs + 2_000L, value));
        }
        List<Cue> cues = bindAuthorEvidence(rawCues,fragments);

        return new Parsed() {
            @Override public List<String> texts() { return texts; }
            @Override public List<Cue> cues() { return cues; }
            @Override public String contentType() { return "application/json; charset=utf-8"; }
            @Override public JSONObject json3() { return root; }
            @Override public String detectedFormat() { return "json3"; }
            @Override public List<RawFragment> rawFragments() { return Collections.unmodifiableList(fragments); }

            @Override
            public byte[] render(List<String> translated) throws Exception {
                requireSameSize(texts, translated);
                for (int i = 0; i < cueEvents.size(); i++) {
                    JSONArray segments = cueEvents.get(i).getJSONArray("segs");
                    JSONObject first = segments.optJSONObject(0);
                    if (first == null) {
                        first = new JSONObject();
                        segments.put(0, first);
                    }
                    first.put("utf8", translated.get(i));
                    for (int j = 1; j < segments.length(); j++) {
                        JSONObject segment = segments.optJSONObject(j);
                        if (segment != null) segment.put("utf8", "");
                    }
                }
                return root.toString().getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static Parsed parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Throwable ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
        catch (Throwable ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
        catch (Throwable ignored) {}

        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        NodeList nodes = document.getElementsByTagName("text");
        boolean timedTextP = nodes.getLength() == 0;
        if (timedTextP) nodes = document.getElementsByTagName("p");

        List<Node> cueNodes = new ArrayList<>();
        List<RawFragment> fragments=new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Cue> rawCues = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element)) continue;
            String value = node.getTextContent();
            if (value.trim().isEmpty()) continue;

            Element element = (Element) node;
            long startMs;
            long endMs;
            if (timedTextP) {
                startMs = firstTime(element, true, "t", "begin", "start");
                long durationMs = firstTime(element, true, "d", "dur");
                long explicitEnd = firstTime(element, true, "end");
                endMs = explicitEnd >= 0L
                        ? explicitEnd
                        : startMs + (durationMs > 0L ? durationMs : 2_000L);
            } else {
                startMs = firstTime(element, false, "start", "begin", "t");
                long durationMs = firstTime(element, false, "dur", "d");
                long explicitEnd = firstTime(element, false, "end");
                endMs = explicitEnd >= 0L
                        ? explicitEnd
                        : startMs + (durationMs > 0L ? durationMs : 2_000L);
            }
            if (startMs < 0L) continue;

            // Only explicit <s t="milliseconds"> onsets are read. Text-only XML stays CUE.
            NodeList segments=element.getElementsByTagName("s");
            if(timedTextP&&segments.getLength()>0){
                for(int j=0;j<segments.getLength();j++){
                    Element segment=(Element)segments.item(j);long offset=firstTime(segment,true,"t");
                    long until=endMs;
                    if(j+1<segments.getLength()){long next=firstTime((Element)segments.item(j+1),true,"t");if(next>=0)until=Math.min(until,startMs+next);}
                    fragments.add(new RawFragment(fragments.size(),i,segment.getTextContent(),offset>=0?startMs+offset:startMs,until,offset>=0,Collections.emptyList()));
                }
            }else fragments.add(new RawFragment(fragments.size(),i,node.getTextContent(),startMs,endMs,false,Collections.emptyList()));

            cueNodes.add(node);
            texts.add(value);
            rawCues.add(new Cue(startMs, endMs, value));
        }
        List<Cue> cues = bindAuthorEvidence(rawCues,fragments);

        return new Parsed() {
            @Override public List<String> texts() { return texts; }
            @Override public List<Cue> cues() { return cues; }
            @Override public String contentType() { return "application/xml; charset=utf-8"; }
            @Override public String detectedFormat() { return "xml"; }
            @Override public List<RawFragment> rawFragments() { return Collections.unmodifiableList(fragments); }

            @Override
            public byte[] render(List<String> translated) throws Exception {
                requireSameSize(texts, translated);
                for (int i = 0; i < cueNodes.size(); i++) {
                    cueNodes.get(i).setTextContent(translated.get(i));
                }

                TransformerFactory transformerFactory = TransformerFactory.newInstance();
                Transformer transformer = transformerFactory.newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(document), new StreamResult(output));
                return output.toByteArray();
            }
        };
    }

    private static Parsed parseBlockFormat(String body, String contentType) {
        WebVttSourceReader.Result timing = contentType.startsWith("text/vtt") ? WebVttSourceReader.read(body) : null;
        String newline = body.contains("\r\n") ? "\r\n" : "\n";
        List<String> blocks = new ArrayList<>(Arrays.asList(body.split("\\r?\\n\\r?\\n", -1)));
        List<Integer> cueIndexes = new ArrayList<>();
        List<RawFragment> fragments=new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<Cue> rawCues = new ArrayList<>();

        int sourceCue=0;
        for (int i = 0; i < blocks.size(); i++) {
            if(contentType.startsWith("text/vtt") && WebVttSourceReader.metadata(blocks.get(i)))continue;
            String[] lines = blocks.get(i).split("\\r?\\n", -1);
            int timeline = -1;
            for (int j = 0; j < lines.length; j++) {
                if (lines[j].contains(" --> ")) {
                    timeline = j;
                    break;
                }
            }
            if (timeline < 0 || timeline + 1 >= lines.length) continue;

            String[] times = lines[timeline].split("\\s+-->\\s+", 2);
            if (times.length != 2) continue;
            long startMs = parseTime(times[0], false);
            String endToken = times[1].trim().split("\\s+", 2)[0];
            long endMs = parseTime(endToken, false);
            if (startMs < 0L || endMs <= startMs) continue;

            String payload = String.join("\n",Arrays.copyOfRange(lines,timeline+1,lines.length));
            String decoded=contentType.startsWith("text/vtt")?WebVttSourceReader.decode(payload):payload;
            fragments.add(new RawFragment(fragments.size(),sourceCue++,decoded,startMs,endMs,false,Collections.emptyList()));
            String text = decoded;
            if (text.trim().isEmpty()) continue;
            cueIndexes.add(i);
            texts.add(text);
            rawCues.add(new Cue(startMs, endMs, text));
        }
        List<Cue> cues = bindAuthorEvidence(rawCues,fragments);

        return new Parsed() {
            @Override public List<String> texts() { return texts; }
            @Override public List<Cue> cues() { return cues; }
            @Override public String contentType() { return contentType; }
            @Override public WebVttSourceReader.Result vtt() { return timing; }
            @Override public String detectedFormat() { return timing==null?"srt":"vtt"; }
            @Override public List<RawFragment> rawFragments() {
                return Collections.unmodifiableList(fragments);
            }

            @Override
            public byte[] render(List<String> translated) {
                requireSameSize(texts, translated);
                for (int i = 0; i < cueIndexes.size(); i++) {
                    int blockIndex = cueIndexes.get(i);
                    String[] lines = blocks.get(blockIndex).split("\\r?\\n", -1);
                    int timeline = -1;
                    for (int j = 0; j < lines.length; j++) {
                        if (lines[j].contains(" --> ")) {
                            timeline = j;
                            break;
                        }
                    }
                    if (timeline < 0) continue;
                    List<String> output = new ArrayList<>();
                    Collections.addAll(output, Arrays.copyOfRange(lines, 0, timeline + 1));
                    output.add(translated.get(i));
                    blocks.set(blockIndex, String.join(newline, output));
                }
                return String.join(newline + newline, blocks).getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static List<Cue> bindAuthorEvidence(List<Cue> raw,List<RawFragment> fragments){
        List<Cue> result=new ArrayList<>();int cursor=0;
        for(Cue cue:raw){List<RawFragment> own=new ArrayList<>();int ordinal=-1;
            while(cursor<fragments.size()&&(fragments.get(cursor).endMs<=cue.startMs||fragments.get(cursor).raw.trim().isEmpty()))cursor++;
            if(cursor<fragments.size())ordinal=fragments.get(cursor).cue;
            while(cursor<fragments.size()&&fragments.get(cursor).cue==ordinal)own.add(fragments.get(cursor++));
            result.add(new Cue(cue.startMs,cue.endMs,cue.text,ordinal,own));
        }return Collections.unmodifiableList(result);
    }

    private static long firstTime(Element element, boolean numericIsMilliseconds, String... names) {
        for (String name : names) {
            String value = element.getAttribute(name);
            if (value == null || value.trim().isEmpty()) continue;
            long parsed = parseTime(value, numericIsMilliseconds);
            if (parsed >= 0L) return parsed;
        }
        return -1L;
    }

    private static long parseTime(String raw, boolean numericIsMilliseconds) {
        if (raw == null) return -1L;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (value.isEmpty()) return -1L;
        try {
            if (value.endsWith("ms")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 1_000d);
            }
            if (value.endsWith("m")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 60_000d);
            }
            if (value.endsWith("h")) {
                return Math.round(Double.parseDouble(value.substring(0, value.length() - 1)) * 3_600_000d);
            }
            if (value.contains(":")) {
                String[] parts = value.split(":");
                double seconds = 0d;
                for (String part : parts) seconds = seconds * 60d + Double.parseDouble(part);
                return Math.round(seconds * 1_000d);
            }
            double numeric = Double.parseDouble(value);
            return Math.round(numeric * (numericIsMilliseconds ? 1d : 1_000d));
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean looksLikeSrt(String trimmed) {
        return trimmed.matches("(?s)^\\d+\\s*\\R\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s+-->.*");
    }

    private static void requireSameSize(List<String> source, List<String> translated) {
        if (source.size() != translated.size()) {
            throw new IllegalArgumentException("翻译条数与原字幕不一致");
        }
    }

    private static String safeType(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
