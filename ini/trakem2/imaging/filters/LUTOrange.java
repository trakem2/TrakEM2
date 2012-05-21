package ini.trakem2.imaging.filters;

import java.util.Map;

public class LUTOrange extends LUTCustom
{
	public LUTOrange() {
		super(1, 0.5f, 0);
	}
	
	public LUTOrange(Map<String,String> params) {
		super(params);
	}
}

