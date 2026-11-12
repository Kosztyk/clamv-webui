package info.trizub.clamav.webclient.util;

/** Display labels and filesystem components have separate bounds. Stored components are ASCII. */
public final class UploadNames {
    private UploadNames() { }
    public static String display(String original) {
        String name=original==null?"upload":original.replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1);
        var clean=new StringBuilder();
        name.codePoints().forEach(code->clean.appendCodePoint(Character.isISOControl(code)?'_':code));
        name=clean.toString();
        if(name.isBlank() || name.equals(".") || name.equals("..")) name="upload";
        int length=Math.min(name.length(),1024);
        if(length<name.length() && Character.isHighSurrogate(name.charAt(length-1))) length--;
        return name.substring(0,length);
    }
    public static String stored(String original) {
        String name=display(original).replaceAll("[^a-zA-Z0-9._-]","_");
        if(name.length()<=120) return name;
        int dot=name.lastIndexOf('.');
        String extension=dot>0 && name.length()-dot<=20?name.substring(dot):"";
        return name.substring(0,120-extension.length())+extension;
    }
}
