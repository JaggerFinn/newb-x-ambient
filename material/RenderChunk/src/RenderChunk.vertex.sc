$input a_color0, a_position, a_texcoord0, a_texcoord1
#ifdef INSTANCING
    $input i_data0, i_data1, i_data2, i_data3
#endif

$output v_color0, v_color1, v_fog, v_refl, v_texcoord0, v_lightmapUV, v_extra

#include <bgfx_shader.sh>
#include <newb_legacy.sh>

uniform vec4 RenderChunkFogAlpha;
uniform vec4 FogAndDistanceControl;
uniform vec4 ViewPositionAndTime;
uniform vec4 FogColor;

void main() {

    mat4 model;
#ifdef INSTANCING
    model = mtxFromCols(i_data0, i_data1, i_data2, i_data3);
#else
    model = u_model[0];
#endif

    vec3 worldPos = mul(model, vec4(a_position, 1.0)).xyz;
    vec4 color;
	vec3 viewDir;

#ifdef RENDER_AS_BILLBOARDS
    worldPos += vec3(0.5, 0.5, 0.5);
    viewDir = normalize(worldPos - ViewPositionAndTime.xyz);
    vec3 boardPlane = normalize(vec3(viewDir.z, 0.0, -viewDir.x));
    worldPos = (worldPos -
        ((((viewDir.yzx * boardPlane.zxy) - (viewDir.zxy * boardPlane.yzx)) *
        (a_color0.z - 0.5)) +
        (boardPlane * (a_color0.x - 0.5))));
    color = vec4(1.0, 1.0, 1.0, 1.0);
#else
    color = a_color0;
#endif

    vec3 modelCamPos = (ViewPositionAndTime.xyz - worldPos);
    float camDis = length(modelCamPos);
	float relativeDist = camDis / FogAndDistanceControl.z;
	viewDir = modelCamPos / camDis;


#ifdef TRANSPARENT
    if(a_color0.a < 0.95) {
		float alphaFadeOut = clamp((camDis / FogAndDistanceControl.w),0.0,1.0);
		color.a = getWaterAlpha(a_color0.rgb);
		color.a = color.a + (0.5-0.5*color.a)*alphaFadeOut;
    };
#endif

	vec3 wPos = worldPos.xyz;
	vec3 cPos = a_position.xyz;
	vec3 bPos = fract(cPos);
	vec3 tiledCpos = cPos*vec3(cPos.x<15.99,cPos.y<15.99,cPos.z<15.99);

    vec4 COLOR = a_color0;
    vec2 uv0 = a_texcoord0;
    vec2 uv1 = a_texcoord1;
	vec2 lit = uv1*uv1;

	bool isColored = (color.g > min(color.r, color.b)) || !(color.r == color.g && color.r == color.b);
	float shade = isColored ? color.g*1.5 : color.g;

	// tree leaves detection
	bool isTree = (isColored && (bPos.x+bPos.y+bPos.z < 0.001)) || (color.a < 0.005 && max(COLOR.g,COLOR.r) > 0.37);
#ifndef ALPHA_TEST
	// detect tree leaves that are not transparent (use texture map)
	isTree = isTree && (uv0.x < 0.1 || uv0.x > 0.9) && uv0.y < 0.3;
#endif

	// environment detections
	bool end = detectEnd(FogColor.rgb);
	bool nether = detectNether(FogColor.rgb, FogAndDistanceControl.xy);
	bool underWater = detectUnderwater(FogColor.rgb, FogAndDistanceControl.xy);
	float rainFactor = detectRain(FogAndDistanceControl.xyz);

	// sky colors
	vec3 zenithCol = getZenithCol(rainFactor, FogColor.rgb);
	vec3 horizonCol = getHorizonCol(rainFactor, FogColor.rgb);
	vec3 horizonEdgeCol = getHorizonEdgeCol(horizonCol, rainFactor, FogColor.rgb);
	if (underWater) {
		vec3 fogcol = getUnderwaterCol(FogColor.rgb);
		zenithCol = fogcol;
		horizonCol = fogcol;
		horizonEdgeCol = fogcol;
	}

	// uses modified biomes_client colors
	bool isWater = COLOR.b == 0.0 && COLOR.a < 0.95;
	float water = float(isWater);

	// time
	highp float t = ViewPositionAndTime.w;

// convert color space to linear-space
#ifdef SEASONS
	isTree = true;

	// season tree leaves are colored in fragment
	color.w *= color.w;
	color = vec4(color.www, 1.0);

	// tree leaves shadow fix
	uv1.y *= 1.00151;
#else
	if (isColored) {
		color.rgb *= color.rgb*1.2;
	}

	// tree and slab shadow fix
	if (isTree || (bPos.y == 0.5 && bPos.x == 0.0)) {
		uv1.y *= 1.00151;
	}
#endif

	vec3 torchColor; // modified by nl_lighting

	// mist (also used underwater to decrease visibility)
	vec4 mistColor = renderMist(horizonEdgeCol, relativeDist, lit.x, rainFactor, nether,underWater,end,FogColor.rgb);

    vec3 light = nl_lighting(torchColor, a_color0.rgb, FogColor.rgb, rainFactor,uv1, lit, isTree,
                 horizonCol, zenithCol, shade, end, nether, underWater);

	mistColor.rgb *= max(0.75, uv1.y);
	mistColor.rgb += 0.3*torchColor*NL_TORCH_INTENSITY*lit.x;

	if (underWater) {
		nl_underwater_lighting(light, mistColor, lit, uv1, tiledCpos, cPos, torchColor, t);
	}

#ifdef ALPHA_TEST
#if defined(NL_PLANTS_WAVE) || defined(NL_LANTERN_WAVE)
	nl_wave(worldPos, light, rainFactor, uv1, lit,
					 uv0, bPos, COLOR, cPos, tiledCpos, t,
					 isColored, camDis, underWater, isTree);
#endif
#endif


	// loading chunks
	relativeDist += RenderChunkFogAlpha.x;

	// slide in (will be disabled next commit)
	worldPos.y -= 100.0*pow(RenderChunkFogAlpha.x,3.0);
	vec4 fogColor = renderFog(horizonEdgeCol, relativeDist, nether, FogColor.rgb, FogAndDistanceControl.xy);

	if (nether) {
		fogColor.rgb = mix(fogColor.rgb, vec3(0.8,0.2,0.12)*1.5,
			lit.x*(1.67-fogColor.a*1.67));
	} else if (!underWater) {

		if (end) {
			fogColor.rgb = vec3(0.16,0.06,0.2);
		}

		// to remove fog in heights
		float fogGradient = 1.0-max(-viewDir.y+0.1,0.0);
		fogGradient *= fogGradient*fogGradient;
		fogColor.a *= fogGradient;
	}

	vec4 pos = mul(u_viewProj, vec4(worldPos, 1.0));

	vec4 refl;
	if (isWater) {
		refl = nl_water(worldPos, color, light, cPos, bPos.y, COLOR, FogColor.rgb, horizonCol,
			  horizonEdgeCol, zenithCol, uv1, t, camDis,
			  rainFactor, tiledCpos, end, torchColor);
	}
	else {
		refl = nl_refl(color, mistColor, lit, uv1, tiledCpos,
			camDis, wPos, viewDir, torchColor, horizonCol,
			zenithCol, rainFactor, FogAndDistanceControl.z, t, pos.xyz);
	}

	color.rgb *= light;

	// mix fog with mist
	mistColor = mix(mistColor,vec4(fogColor.rgb,1.0),fogColor.a);

	v_extra.r = shade;
	v_extra.g = worldPos.y;
	v_extra.b = water;
	v_refl = refl;
    v_texcoord0 = a_texcoord0;
    v_lightmapUV = a_texcoord1;
    v_color0 = color;
	v_color1 = a_color0;
    v_fog = mistColor;
    gl_Position = pos;
}
