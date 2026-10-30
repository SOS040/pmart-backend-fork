package com.pmart.product.content.domain;

/** 이미지 역할. 고객 화면 순서도 이 순서(대표 → 상세설명 → 상세정보 표)다. */
public enum ImageRole {
  /** 대표(카드) 사진. 1장, sortOrder 0. */
  MAIN,
  /** 상세설명(롱이미지 조각). sortOrder 순. */
  DESCRIPTION,
  /** 상세정보 표. */
  DETAIL
}
